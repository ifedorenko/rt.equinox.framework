/*******************************************************************************
 * Copyright (c) 2008, 2009 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package test.link.e.client;

import org.osgi.framework.*;
import test.link.e.service1.Service1;
import test.link.e.service2.Service2;
import test.link.e.service3.Service3;

public class Activator implements BundleActivator {

	public void start(BundleContext context) throws Exception {
		Service1 service1 = (Service1) checkService(Service1.class.getName(), context);
		if (service1 == null)
			throw new Exception("missing Service1"); //$NON-NLS-1$
		Service2 service2 = (Service2) checkService(Service2.class.getName(), context);
		if (service2 == null)
			throw new Exception("missing Service2"); //$NON-NLS-1$
		Service3 service3 = (Service3) checkService(Service3.class.getName(), context);
		if (service3 == null)
			throw new Exception("missing Service3"); //$NON-NLS-1$
	}

	private Object checkService(String name, BundleContext context) throws BundleException {
		ServiceReference ref = context.getServiceReference(name);
		if (ref == null)
			throw new BundleException("Missing service " + name); //$NON-NLS-1$
		Object service = context.getService(ref);
		if (service != null)
			context.ungetService(ref);
		return service;
	}

	public void stop(BundleContext context) throws Exception {
		// TODO Auto-generated method stub

	}

}
