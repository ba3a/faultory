---
name: skin-actions
description: How sprites resolve in Faultory — SkinDefinition, the *ActionResolver family, SkinFrameResolver's fixed degradation order, the SpriteAction enum that is the single source for action names / catalog membership / stand-ins, sockets and interactions versus actions, raw-art layout and atlas baking, and the SkinActionCatalog rule that every action a resolver can request must also be authorable. Use when adding or changing an action, a resolver, a renderer or a ShopFloorLayer, when art fails to appear or falls back to ShapeRenderer primitives, and when working under raw-art/ or assets/skins/.
---

# Sprites, actions and skins

Workers, machines, products and belt tiles are all drawn the same way: a skin id resolves to a
`SkinDefinition` (`assets/skins/<id>.json`), an `*ActionResolver` maps entity state to an action
name and an orientation, and `SkinFrameResolver` picks the clip to draw.

## The degradation order

`SkinFrameResolver` degrades in a fixed order, so partially authored art still renders:

1. Requested action facing the requested way
2. That action facing `SOUTH`
3. That action at the nearest authored orientation by turning order — clockwise neighbour,
   counter-clockwise neighbour, opposite
4. Those same three steps again for any **stand-in** the action declares
5. Those same three steps for `idle`

Stand-ins exist because idle is the wrong substitute for anything that plays while the entity is
moving or off its feet — an unauthored `pursue` borrows `walk` rather than freezing a guard
mid-stride.

Only when nothing resolves does the entity fall through to the `ShapeRenderer` primitives in
`PlacedObjectRenderer` / `GridBackgroundRenderer`. Set `DebugFlags.forceShapeRendering` (F9 on the
shop floor, or `-Dfaultory.debug.shapes=true`) to force that fallback everywhere.

## Action names come from SpriteAction

Every action is one entry in the `SpriteAction` enum, carrying its `id`, the `SpriteKind`s that may
request it, and its optional stand-in. Name it `SpriteAction.WALK.id` at the call site, never a
string literal. `SpriteAction.faultOverlayFor(faultReason)` / `SpriteAction.forBeltShape(shape)` are
the two state→action mappers that live on the enum's companion.

Products additionally support `fault_defect` / `fault_sabotage` overlay masks drawn over the base
frame; without mask art the base sprite is tinted instead. Belt tiles have no catalog entry —
`BeltTopology` derives flow direction and tile shape from `ShopGrid.orderedBeltPaths`, and the skin
is `ConveyorBelt.skin` or `AssetPaths.defaultBeltSkin`.

## When a resolver learns a new action, add a SpriteAction entry

`SkinActionCatalog`'s per-kind lists (`worker` / `machine` / `product` / `belt`) and
`SkinFrameResolver`'s stand-in chain both **derive** from `SpriteAction`, so one entry catalogues
the action and gives it its fallback — nothing to keep in sync by hand. `SkinActionCatalog` is what
the editor turns into animation grid rows (`AnimationTargets` maps a selected asset to its grids;
belts hang off the blueprint selection, and `SkinActionCatalog.workerActions` merges in both halves
of every interaction in `content/interactions.json`).

An action the runtime asks for but the enum does not list is an animation that never plays.
`SpriteActionTest` pins the table itself; `SkinActionCatalogTest` enforces the runtime↔author
contract by driving the resolvers over every `UnitPhase`, `BeltRidePhase`, `InteractionRole`,
`ShopProductState` and `BeltTileShape`.

## Two things that look like actions but are not

- **Carrying** is the payload riding the `hands` socket over the ordinary pose.
- **Handing over** is an interaction, whose two halves are authored per interaction rather than as
  constants.

Reach for a socket or an interaction before adding an action.

## Where art lives

Raw art lives in `raw-art/<skinId>/<action>_<orientation-lowercase>/NNN.png` and is baked with the
editor's Tools → "Bake atlas..." dialog, which works for any skin id.

Right-clicking a cell of the editor's animation grid offers **"Mirror into <ORIENTATION>"**, which
writes a left-to-right flip of that cell's frames, cutout layers and sockets into another
orientation as ordinary raw art and re-bakes. It is a corner-cutting tool for poses whose two
facings differ by nothing but the flip — authoring a distinct animation per orientation is still
the norm — and it deliberately leaves no trace: the copy is indistinguishable from drawn art, and
redrawing the source does not update it. Nothing in `core` knows about it; the flip happens once,
at authoring time, never while drawing.

## Layer discipline

Sprite layers resolve what they will draw in `ShopFloorLayer.prepare`, which runs before every draw
pass; that is what lets the shape layers suppress themselves for sprite-backed entities in the same
frame. Do not resolve sprites in `drawSprite`.

Read entity lists and resolved render positions from `ctx.frame` (the per-frame `ShopFloorFrame`,
captured once in `ShopFloorScreen.render()`), not by iterating `ShopFloor` or calling
`ShopFloorGeometry.renderPositionFor` again — a read-only layer then needs nothing else injected.
Sprite-specific geometry (sockets, handover interpolation, machine footprint centres) is not on the
frame and stays resolved per fragment in `EntitySpriteLayer`.
