package com.oms.matching.book;

/**
 * All resting orders at one price, in arrival order.
 *
 * <p>An intrusive doubly-linked list plus a running total. The running total is the reason
 * this class exists rather than a bare head pointer: a depth snapshot and a fill-or-kill
 * feasibility check both need the quantity at a level, and recomputing it by walking the
 * queue turns an O(1) question into O(orders at that level). Maintaining it on every
 * insert, fill and cancel costs one addition each.
 *
 * <p>Package-private and not thread safe - one writer per book (ADR 0005).
 */
final class PriceLevel {

    final long priceTicks;

    OrderNode head;
    OrderNode tail;

    long totalQuantity;
    int orderCount;

    PriceLevel(long priceTicks) {
        this.priceTicks = priceTicks;
    }

    boolean isEmpty() {
        return head == null;
    }

    /** Appends at the tail: arrival order is queue order, which is time priority. */
    void append(OrderNode node) {
        node.level = this;
        node.prev = tail;
        node.next = null;
        if (tail == null) {
            head = node;
        } else {
            tail.next = node;
        }
        tail = node;
        totalQuantity += node.remaining;
        orderCount++;
    }

    /**
     * Unlinks {@code node} in O(1).
     *
     * <p>The node carries its own links, so there is nothing to search for. This is the
     * operation that makes cancels cheap, and cancels dominate the message mix on a real
     * venue - orders placed and pulled without ever trading outnumber executions by a wide
     * margin.
     */
    void unlink(OrderNode node) {
        if (node.prev != null) {
            node.prev.next = node.next;
        } else {
            head = node.next;
        }
        if (node.next != null) {
            node.next.prev = node.prev;
        } else {
            tail = node.prev;
        }
        totalQuantity -= node.remaining;
        orderCount--;
        node.prev = null;
        node.next = null;
        node.level = null;
    }

    /** Records a partial fill against the head order without unlinking it. */
    void reduce(long quantity) {
        totalQuantity -= quantity;
    }

    @Override
    public String toString() {
        return "PriceLevel[" + priceTicks + " qty=" + totalQuantity + " orders=" + orderCount + "]";
    }
}
