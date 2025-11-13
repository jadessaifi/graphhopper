/*
 *  Licensed to GraphHopper GmbH under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for
 *  additional information regarding copyright ownership.
 *
 *  GraphHopper GmbH licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except in
 *  compliance with the License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.graphhopper.coll;

import com.carrotsearch.hppc.cursors.IntCursor;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.lang.reflect.Field;
import java.util.Iterator;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests combinés : cas fonctionnels (existants) + cas mockés (Mockito).
 */
public class GHSortedCollectionTest {

    // ========== TES TESTS EXISTANTS (boîte noire) ==========

    @Test
    public void testPoll() {
        GHSortedCollection instance = new GHSortedCollection();
        assertTrue(instance.isEmpty());
        instance.insert(0, 10);
        assertEquals(10, instance.peekValue());
        assertEquals(1, instance.getSize());
        instance.insert(1, 2);
        assertEquals(2, instance.peekValue());
        assertEquals(1, instance.pollKey());
        assertEquals(0, instance.pollKey());
        assertEquals(0, instance.getSize());
    }

    @Test
    public void testInsert() {
        GHSortedCollection instance = new GHSortedCollection();
        assertTrue(instance.isEmpty());
        instance.insert(0, 10);
        assertEquals(1, instance.getSize());
        assertEquals(10, instance.peekValue());
        assertEquals(0, instance.peekKey());
        instance.update(0, 10, 2);
        assertEquals(2, instance.peekValue());
        assertEquals(1, instance.getSize());
        instance.insert(0, 11);
        assertEquals(2, instance.peekValue());
        assertEquals(2, instance.getSize());
        instance.insert(1, 0);
        assertEquals(0, instance.peekValue());
        assertEquals(3, instance.getSize());
    }

    @Test
    public void testUpdate() {
        GHSortedCollection instance = new GHSortedCollection();
        assertTrue(instance.isEmpty());
        instance.insert(0, 10);
        instance.insert(1, 11);
        assertEquals(10, instance.peekValue());
        assertEquals(2, instance.getSize());
        instance.update(0, 10, 12);
        assertEquals(11, instance.peekValue());
        assertEquals(2, instance.getSize());
    }

    // ========== OUTILS RÉFLEXION POUR LES TESTS MOCKITO ==========

    @SuppressWarnings("unchecked")
    private static TreeMap<Integer, GHIntHashSet> internalMap(GHSortedCollection pq) {
        try {
            Field f = GHSortedCollection.class.getDeclaredField("map");
            f.setAccessible(true);
            return (TreeMap<Integer, GHIntHashSet>) f.get(pq);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void setSize(GHSortedCollection pq, int size) {
        try {
            Field f = GHSortedCollection.class.getDeclaredField("size");
            f.setAccessible(true);
            f.setInt(pq, size);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ========== NOUVEAUX TESTS MOCKITO (≥2 classes simulées) ==========

    /**
     * Mock de GHIntHashSet + Iterator<IntCursor> :
     * - Simule un bucket (priorité 5) dont l'iterator.next() renvoie la clé 7,
     * - pollKey() doit itérer, supprimer 7, constater isEmpty() et retirer le bucket de la TreeMap,
     * - size doit décrémenter à 0.
     */
    @Test
    void pollKey_iteratesRemovesAndCleansBucket_withMocks() {
        GHSortedCollection pq = new GHSortedCollection();
        TreeMap<Integer, GHIntHashSet> map = internalMap(pq);

        GHIntHashSet set5 = mock(GHIntHashSet.class);

        @SuppressWarnings("unchecked")
        Iterator<IntCursor> it = (Iterator<IntCursor>) mock(Iterator.class);
        IntCursor c = new IntCursor();
        c.value = 7;

        when(set5.isEmpty()).thenReturn(false, true); // avant remove: non vide, après: vide
        when(set5.iterator()).thenReturn(it);
        when(it.next()).thenReturn(c);
        when(set5.remove(7)).thenReturn(true);

        map.put(5, set5);
        setSize(pq, 1);

        int polled = pq.pollKey();

        assertEquals(7, polled);
        assertEquals(0, pq.getSize());
        assertTrue(pq.isEmpty());
        assertFalse(map.containsKey(5), "le bucket vide doit être supprimé");

        InOrder order = inOrder(set5, it);
        order.verify(set5).isEmpty();
        order.verify(set5).iterator();
        order.verify(it).next();
        order.verify(set5).remove(7);
        order.verify(set5).isEmpty();
        verifyNoMoreInteractions(set5, it);
    }

    /**
     * Mock de GHIntHashSet + Iterator<IntCursor> :
     * - Simule update(42, 10, 20) avec 2 buckets déjà dans la map,
     * - remove(42) sur l'ancien bucket (10) le rend vide -> il est retiré,
     * - add(42) sur le nouveau bucket (20) réussit,
     * - size reste constant (1), et la map contient 20 mais plus 10.
     */
    @Test
    void update_callsRemoveOnOldBucket_andAddOnNewBucket_withMocks() {
        GHSortedCollection pq = new GHSortedCollection();
        TreeMap<Integer, GHIntHashSet> map = internalMap(pq);

        GHIntHashSet set10 = mock(GHIntHashSet.class);
        GHIntHashSet set20 = mock(GHIntHashSet.class);

        @SuppressWarnings("unchecked")
        Iterator<IntCursor> dummyIt = (Iterator<IntCursor>) mock(Iterator.class);
        when(set10.iterator()).thenReturn(dummyIt);
        when(set20.iterator()).thenReturn(dummyIt);

        when(set10.remove(42)).thenReturn(true);
        when(set10.isEmpty()).thenReturn(true);

        when(set20.add(42)).thenReturn(true);
        when(set20.isEmpty()).thenReturn(false);

        map.put(10, set10);
        map.put(20, set20);
        setSize(pq, 1);

        pq.update(42, 10, 20);

        verify(set10, times(1)).remove(42);
        verify(set20, times(1)).add(42);

        assertFalse(map.containsKey(10), "bucket (10) doit être retiré");
        assertTrue(map.containsKey(20), "bucket (20) doit rester");
        assertEquals(1, pq.getSize());
        assertFalse(pq.isEmpty());
    }
}
