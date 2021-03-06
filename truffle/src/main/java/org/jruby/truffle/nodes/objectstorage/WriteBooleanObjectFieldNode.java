/*
 * Copyright (c) 2013, 2015 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */
package org.jruby.truffle.nodes.objectstorage;

import com.oracle.truffle.api.nodes.InvalidAssumptionException;
import com.oracle.truffle.api.nodes.NodeCost;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.object.BooleanLocation;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.object.FinalLocationException;
import com.oracle.truffle.api.object.Shape;

@NodeInfo(cost = NodeCost.POLYMORPHIC)
public class WriteBooleanObjectFieldNode extends WriteObjectFieldChainNode {

    private final Shape expectedLayout;
    private final Shape newLayout;
    private final BooleanLocation storageLocation;

    public WriteBooleanObjectFieldNode(Shape expectedLayout, Shape newLayout, BooleanLocation storageLocation, WriteObjectFieldNode next) {
        super(next);
        this.expectedLayout = expectedLayout;
        this.newLayout = newLayout;
        this.storageLocation = storageLocation;
    }

    @Override
    public void execute(DynamicObject object, boolean value) {
        try {
            expectedLayout.getValidAssumption().check();
            newLayout.getValidAssumption().check();
        } catch (InvalidAssumptionException e) {
            replace(next);
            next.execute(object, value);
            return;
        }

        if (object.getShape() == expectedLayout) {
            try {
                if (newLayout == expectedLayout) {
                    storageLocation.setBoolean(object, value, expectedLayout);
                } else {
                    storageLocation.setBoolean(object, value, expectedLayout, newLayout);
                }
            } catch (FinalLocationException e) {
                replace(next, "!final").execute(object, value);
            }
        } else {
            next.execute(object, value);
        }
    }

    @Override
    public void execute(DynamicObject object, Object value) {
        if (value instanceof Boolean) {
            execute(object, (boolean) value);
        } else {
            next.execute(object, value);
        }
    }

}
