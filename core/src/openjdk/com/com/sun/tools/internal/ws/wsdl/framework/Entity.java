/*
 * Portions Copyright 2006 Sun Microsystems, Inc.  All Rights Reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Sun designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Sun in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Sun Microsystems, Inc., 4150 Network Circle, Santa Clara,
 * CA 95054 USA or visit www.sun.com if you need additional information or
 * have any questions.
 */

package com.sun.tools.internal.ws.wsdl.framework;

import java.util.HashMap;
import java.util.Map;

/**
 * An entity, typically corresponding to an XML element.
 *
 * @author WS Development Team
 */
public abstract class Entity implements Elemental {

    public Entity() {
    }

    public Object getProperty(String key) {
        if (_properties == null)
            return null;
        return _properties.get(key);
    }

    public void setProperty(String key, Object value) {
        if (value == null) {
            removeProperty(key);
            return;
        }

        if (_properties == null) {
            _properties = new HashMap();
        }
        _properties.put(key, value);
    }

    public void removeProperty(String key) {
        if (_properties != null) {
            _properties.remove(key);
        }
    }

    public void withAllSubEntitiesDo(EntityAction action) {
        // no-op by default
    }

    public void withAllQNamesDo(QNameAction action) {
        action.perform(getElementName());
    }

    public void withAllEntityReferencesDo(EntityReferenceAction action) {
        // no-op by default
    }

    public abstract void validateThis();

    protected void failValidation(String key) {
        throw new ValidationException(key, getElementName().getLocalPart());
    }

    protected void failValidation(String key, String arg) {
        throw new ValidationException(
            key,
            new Object[] { arg, getElementName().getLocalPart()});
    }

    private Map _properties;
}