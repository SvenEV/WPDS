/*******************************************************************************
 * Copyright (c) 2018 Fraunhofer IEM, Paderborn, Germany.
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *  
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Johannes Spaeth - initial API and implementation
 *******************************************************************************/
package test.cases.statics;

import org.junit.Ignore;
import org.junit.Test;

import test.cases.fields.Alloc;
import test.core.AbstractBoomerangTest;

public class SimpleSingleton extends AbstractBoomerangTest {

	@Test
	@Ignore
	public void singletonDirect(){
		Alloc singleton = alloc;
		queryForAndNotEmpty(singleton);
	}
	private static Alloc alloc = new Alloc();
	@Test
	public void simpleWithAssign(){
		alloc = new Alloc();
	    Object b = alloc;
		queryFor(b);
	}
	@Test
	public void simpleWithAssign2(){
		alloc = new Alloc();
	    Object b = alloc;
	    Object a = alloc;
		queryFor(b);
	}
}
