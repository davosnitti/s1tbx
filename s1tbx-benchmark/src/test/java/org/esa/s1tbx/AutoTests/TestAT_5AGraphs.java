/*
 * Copyright (C) 2014 by Array Systems Computing Inc. http://www.array.ca
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, see http://www.gnu.org/licenses/
 */
package org.esa.s1tbx.AutoTests;

import org.esa.s1tbx.TestAutomatedGraphProcessing;

/**
 * Runs graphs as directed by the tests config file
 */
public class TestAT_5AGraphs extends TestAutomatedGraphProcessing {

    protected String getTestFileName() {
        return "autoTest_AT_5A.tests";
    }
}
