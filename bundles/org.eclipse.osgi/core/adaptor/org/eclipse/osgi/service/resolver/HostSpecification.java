/*******************************************************************************
 * Copyright (c) 2003, 2004 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.osgi.service.resolver;

/**
 * A representation of one host bundle constraint as seen in a 
 * bundle manifest and managed by a state and resolver.
 */
public interface HostSpecification extends VersionConstraint {
	/**
	 * Returns the list of host BundleDescriptions that satisfy this HostSpecification
	 * @return the list of host BundleDescriptions that satisfy this HostSpecification
	 */
	public BundleDescription[] getHosts();
}