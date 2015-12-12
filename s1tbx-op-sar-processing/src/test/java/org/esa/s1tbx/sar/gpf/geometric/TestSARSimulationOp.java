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
package org.esa.s1tbx.sar.gpf.geometric;

import org.esa.s1tbx.commons.S1TBXTests;
import org.esa.s1tbx.commons.TestData;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.engine_utilities.gpf.TestProcessor;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertNotNull;

/**
 * Unit test for SAR Simulation Operator.
 */
public class TestSARSimulationOp {

    static {
        TestUtils.initTestEnvironment();
    }
    private final static OperatorSpi spi = new SARSimulationOp.Spi();
    private final static TestProcessor testProcessor = S1TBXTests.createS1TBXTestProcessor();

    private final static String inputPathWSM = TestData.inputSAR + "\\subset_1_of_ENVISAT-ASA_WSM_1PNPDE20080119_093446_000000852065_00165_30780_2977.dim";
    private final static String expectedPathWSM = S1TBXTests.rootPathTestProducts + "\\expected\\subset_1_of_ENVISAT-ASA_WSM_1PNPDE20080119_093446_000000852065_00165_30780_2977_SIM.dim";

    private String[] productTypeExemptions = {"_BP", "XCA", "WVW", "WVI", "WVS", "WSS", "DOR_VOR_AX"};
    private String[] exceptionExemptions = {"not supported", "not be map projected", "outside of SRTM valid area",
                "Source product should first be deburst"};

    /**
     * Processes a product and compares it to processed product known to be correct
     *
     * @throws Exception general exception
     */
    @Test
    public void testProcessing() throws Exception {
        final File inputFile = TestData.inputASAR_WSM;
        if (!inputFile.exists()) {
            TestUtils.skipTest(this, inputPathWSM + " not found");
            return;
        }
        final Product sourceProduct = TestUtils.readSourceProduct(inputFile);

        final SARSimulationOp op = (SARSimulationOp) spi.createOperator();
        assertNotNull(op);
        op.setSourceProduct(sourceProduct);

        // get targetProduct: execute initialize()
        final Product targetProduct = op.getTargetProduct();
        TestUtils.verifyProduct(targetProduct, true, true, true);

        final float[] expected = new float[] {
                0.0057023214f,
                4.8023136E-4f,
                0.0051902845f,
                8.185921E-4f };
        TestUtils.comparePixels(targetProduct, targetProduct.getBandAt(0).getName(), expected);
    }

    @Test
    public void testProcessAllASAR() throws Exception {
        testProcessor.testProcessAllInPath(spi, S1TBXTests.rootPathsASAR, productTypeExemptions, exceptionExemptions);
    }

    @Test
    public void testProcessAllERS() throws Exception {
        testProcessor.testProcessAllInPath(spi, S1TBXTests.rootPathsERS, productTypeExemptions, exceptionExemptions);
    }

    @Test
    public void testProcessAllALOS() throws Exception {
        testProcessor.testProcessAllInPath(spi, S1TBXTests.rootPathsALOS, "ALOS PALSAR CEOS", null, exceptionExemptions);
    }

    @Test
    public void testProcessAllRadarsat2() throws Exception {
        testProcessor.testProcessAllInPath(spi, S1TBXTests.rootPathsRadarsat2, null, exceptionExemptions);
    }

    @Test
    public void testProcessAllTerraSARX() throws Exception {
        testProcessor.testProcessAllInPath(spi, S1TBXTests.rootPathsTerraSarX, null, exceptionExemptions);
    }

    @Test
    public void testProcessAllCosmo() throws Exception {
        testProcessor.testProcessAllInPath(spi, S1TBXTests.rootPathsCosmoSkymed, null, exceptionExemptions);
    }

    @Test
    public void testProcessAllSentinel1() throws Exception {
        testProcessor.testProcessAllInPath(spi, S1TBXTests.rootPathsSentinel1, null, exceptionExemptions);
    }
}
