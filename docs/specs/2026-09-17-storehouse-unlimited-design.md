# Storehouse with unlimited storage per material

Requested in play-testing on 2026-09-17: the 27-slot storehouse fills up (360 oak logs take 6 slots). The user
approved this design.

## Storage

- The block entity keeps an ordered list of entries. Each entry is a one-item prototype (item plus data
  components) and a `long` count, so named, enchanted or damaged items stay in their own entries.
- It is no longer a vanilla `Container`. Hoppers and item pipes use an `IItemHandler` capability that accepts
  everything and extracts nothing, so extraction still only happens through a menu action, which charges debt.
- It saves as `Stored: [{item, count}]`. A save that still has the old 27-slot `Items` list is merged into
  entries when it loads.
- Breaking the block charges the breaker debt for the total count, and drops every entry as item stacks.
- The citizen API stays the same (`insertFromCitizen`, `extractForCitizen` capped at one stack, `hasAll`), and
  counts are longs.

## Menu and screen

- The menu holds only the 36 player inventory slots, laid out like a six-row chest.
- The server sends the entries to the viewer (`StorehouseContentsPayload`) when the menu opens and whenever the
  contents change.
- The client screen draws the entries on a 9x6 grid with a scrollbar and mouse-wheel scrolling. Counts are
  abbreviated (1.2k, 3.4M), and the tooltip shows the exact count.
- Clicks send `StorehouseActionPayload(containerId, prototype, button)`, and the server decides:
  - **left click:** with an empty cursor, takes one stack to the cursor; with a stack on the cursor, deposits it;
  - **right click:** with an empty cursor, takes half a stack; with a stack on the cursor, deposits one item;
  - **shift-click:** takes as many as fit into the player inventory;
  - **shift-click on a player slot:** deposits that stack.
- Every action settles credit and debt by the change in total count, as the old slot clicks did.

## Not in scope

- Search box and sorting options.
- Comparator output.
- Keeping the contents inside the dropped block item. Breaking a very full storehouse drops many item entities.
