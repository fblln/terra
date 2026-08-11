# ReservationFlowST

**Description:** Reservation of stock end to end: the HTTP surface, the Mongo read model and the stock-moves topic, asserted together against a live `fulfilment` environment.

**Before test execution steps:**

| Step | Action | Result |
| - | - | - |
| 1. | Insert a SKU with onHand=3, reserved=0 under this test's own id | Inventory holds stock nothing else in the run can see |
| 2. | Insert a NEW order for 2 of that SKU under this test's own id | An order exists to reserve against |

**Labels:**

* [inventory](../../labels/inventory.md)
* [shipping](../../labels/shipping.md)

<hr style="border:1px solid">

## a reservation moves stock, emits an event and lands in the read model

**Description:** A reservation debits stock, emits StockMoved and leaves the order RESERVED.

**Steps:**

| Step | Action | Result |
| - | - | - |
| 1. | Checkpoint the stock-moves topic | A mark to read forward from, so other tests' events are not seen |
| 2. | GET /health on orders-api | 200 — the service is up and configured |
| 3. | Reserve 2 of the SKU | StockMoved{delta=-2} is published, inventory reads onHand=1 reserved=2 |
| 4. | Await the order in the read model | Order state is RESERVED and still carries the SKU |

**Labels:**

* [regression](../../labels/regression.md)
* [inventory](../../labels/inventory.md)


## an over-reservation is rejected and emits nothing

**Description:** An order for more than is on hand is rejected, and no StockMoved is emitted.

**Steps:**

| Step | Action | Result |
| - | - | - |
| 1. | Checkpoint the stock-moves topic | A mark to assert an absence from |
| 2. | Await the order reaching REJECTED | reason is INSUFFICIENT_STOCK |
| 3. | Read stock-moves forward from the mark for 2s | Times out — nothing was emitted for this SKU |
| 4. | Re-read the SKU | onHand is still 3; the rejection moved nothing |

**Labels:**

* [regression](../../labels/regression.md)
* [inventory](../../labels/inventory.md)

