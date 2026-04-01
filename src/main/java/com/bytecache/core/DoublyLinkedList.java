package com.bytecache.core;

public class DoublyLinkedList<K, V> {
    private final CacheNode<K, V> head;
    private final CacheNode<K, V> tail;

    public DoublyLinkedList() {
        head = new CacheNode<>(null, null, 0);
        tail = new CacheNode<>(null, null, 0);
        head.next = tail;
        tail.prev = head;
    }

    public void addFirst(CacheNode<K, V> node) {
        node.next = head.next;
        node.prev = head;
        head.next.prev = node;
        head.next = node;
    }

    public void remove(CacheNode<K, V> node) {
        if (node.prev != null && node.next != null) {
            node.prev.next = node.next;
            node.next.prev = node.prev;
        }
    }

    public CacheNode<K, V> removeLast() {
        if (tail.prev == head) {
            return null; // list is empty
        }
        CacheNode<K, V> last = tail.prev;
        remove(last);
        return last;
    }

    public void moveToFirst(CacheNode<K, V> node) {
        remove(node);
        addFirst(node);
    }

    public boolean isEmpty() {
        return head.next == tail;
    }
}
