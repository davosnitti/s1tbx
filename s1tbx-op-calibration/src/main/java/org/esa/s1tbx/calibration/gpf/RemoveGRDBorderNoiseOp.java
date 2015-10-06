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
package org.esa.s1tbx.calibration.gpf;

import com.bc.ceres.core.ProgressMonitor;
import org.esa.s1tbx.insar.gpf.Sentinel1Utils;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.datamodel.VirtualBand;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.Tile;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.core.gpf.annotations.Parameter;
import org.esa.snap.core.gpf.annotations.SourceProduct;
import org.esa.snap.core.gpf.annotations.TargetProduct;
import org.esa.snap.core.util.ProductUtils;
import org.esa.snap.datamodel.AbstractMetadata;
import org.esa.snap.datamodel.Unit;
import org.esa.snap.gpf.OperatorUtils;
import org.esa.snap.gpf.TileIndex;
import org.esa.snap.util.Maths;

import java.awt.Rectangle;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

/**
 * Mask "No-value" pixels for near-range region in a Sentinel-1 Level-1 GRD product.
 *
 * [1] "Masking No-value Pixels on GRD Products generated by the Sentinel-1 ESA IPF",
 *     OI-MPC-OTH-0243-S1-Border-Masking-MPCS-916.
 */
@OperatorMetadata(alias = "Remove-GRD-Border-Noise",
        category = "Radar/Sentinel-1 TOPS",
        authors = "Jun Lu, Luis Veci",
        copyright = "Copyright (C) 2014 by Array Systems Computing Inc.",
        description = "Mask no-value pixels for GRD product")
public final class RemoveGRDBorderNoiseOp extends Operator {

    @SourceProduct
    private Product sourceProduct;

    @TargetProduct
    private Product targetProduct;

    @Parameter(description = "The list of polarisations", label = "Polarisations")
    private String[] selectedPolarisations;

    @Parameter(description = "The border margin limit", defaultValue = "500", label = "Border margin limit[pixels]")
    private int borderLimit = 500;

    @Parameter(description = "The trim threshold", defaultValue = "0.5", label = "Threshold")
    private double trimThreshold = 0.5;

    private MetadataElement absRoot = null;
    private MetadataElement origMetadataRoot = null;
    private int sourceImageWidth = 0;
    private int sourceImageHeight = 0;
    private double version = 0.0f;
    private double scalingFactor = 0.0;
    private double noDataValue = 0;
    private String coPolarization = null;
    private Sentinel1Utils.NoiseVector noiseVector = null;
    private double[] noiseLUT = null;
    private Band coPolBand = null;
    private boolean thermalNoiseCorrectionPerformed = false;

    /**
     * Default constructor. The graph processing framework
     * requires that an operator has a default constructor.
     */
    public RemoveGRDBorderNoiseOp() {
    }

    /**
     * Initializes this operator and sets the one and only target product.
     * <p>The target product can be either defined by a field of type {@link Product}
     * annotated with the {@link TargetProduct TargetProduct} annotation or
     * by calling {@link #setTargetProduct} method.</p>
     * <p>The framework calls this method after it has created this operator.
     * Any client code that must be performed before computation of tile data
     * should be placed here.</p>
     *
     * @throws OperatorException If an error occurs during operator initialisation.
     * @see #getTargetProduct()
     */
    @Override
    public void initialize() throws OperatorException {

        try {
            absRoot = AbstractMetadata.getAbstractedMetadata(sourceProduct);
            origMetadataRoot = AbstractMetadata.getOriginalProductMetadata(sourceProduct);

            sourceImageWidth = sourceProduct.getSceneRasterWidth();
            sourceImageHeight = sourceProduct.getSceneRasterHeight();

            checkSourceProductValidity();

            getIPFVersion();

            getProductCoPolarization();

            getThermalNoiseCorrectionFlag();

            if (!thermalNoiseCorrectionPerformed) {
                getThermalNoiseVector();
            }

            computeNoiseScalingFactor();

            computeNoiseLUT();

            createTargetProduct();

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    /**
     * Check source product validity.
     */
    private void checkSourceProductValidity() {

        final String mission = absRoot.getAttributeString(AbstractMetadata.MISSION);
        if (!mission.startsWith("SENTINEL-1")) {
            throw new OperatorException("Input should be a Sentinel-1 GRD product.");
        }

        final String productType = absRoot.getAttributeString(AbstractMetadata.PRODUCT_TYPE);
        if (!productType.equals("GRD")) {
            throw new OperatorException("Input should be a GRD product.");
        }

        final String productName = absRoot.getAttributeString(AbstractMetadata.PRODUCT);
        final String level = productName.substring(12, 14);
        if (!level.equals("1S")) {
            throw new OperatorException("Input should be a level-1 product.");
        }

        final String polarization = productName.substring(14, 16);
        if (!polarization.equals("SH") && !polarization.equals("SV") && !polarization.equals("DH") &&
                !polarization.equals("DV") && !polarization.equals("HH") && !polarization.equals("HV") &&
                !polarization.equals("VV") && !polarization.equals("VH")) {
            throw new OperatorException("Unknown source product polarization");
        }

        if (absRoot.getAttribute(AbstractMetadata.abs_calibration_flag).getData().getElemBoolean()) {
            throw new OperatorException("Cannot apply the operator to calibrated product.");
        }
    }

    /**
     * Get IPF version.
     */
    private void getIPFVersion() {

        final String procSysId = absRoot.getAttributeString(AbstractMetadata.ProcessingSystemIdentifier);
        version = Double.valueOf(procSysId.substring(procSysId.lastIndexOf(" ")));
    }

    /**
     * Get product co-polarization.
     */
    private void getProductCoPolarization() {

        final String[] sourceBandNames = sourceProduct.getBandNames();
        for (String bandName:sourceBandNames) {
            if (bandName.contains("HH")) {
                coPolarization = "HH";
                coPolBand = sourceProduct.getBand(bandName);
                break;
            } else if (bandName.contains("VV")) {
                coPolarization = "VV";
                coPolBand = sourceProduct.getBand(bandName);
                break;
            }
        }

        if (coPolarization == null) {
            throw new OperatorException("Input product does not contain band with HH or VV polarization");
        }
        noDataValue = coPolBand.getNoDataValue();
    }

    /**
     * Get thermal noise correction flag from the original product metadata.
     */
    private void getThermalNoiseCorrectionFlag() {

        final MetadataElement annotationElem = origMetadataRoot.getElement("annotation");
        final MetadataElement[] annotationDataSetListElem = annotationElem.getElements();
        final MetadataElement productElem = annotationDataSetListElem[0].getElement("product");
        final MetadataElement imageAnnotationElem = productElem.getElement("imageAnnotation");
        final MetadataElement processingInformationElem = imageAnnotationElem.getElement("processingInformation");

        thermalNoiseCorrectionPerformed = Boolean.parseBoolean(
                processingInformationElem.getAttribute("thermalNoiseCorrectionPerformed").getData().getElemString());
    }

    /**
     * Get the middle thermal noise vector from the original product metadata.
     */
    private void getThermalNoiseVector() {

        final MetadataElement noiseElem = origMetadataRoot.getElement("noise");
        final MetadataElement[] noiseDataSetListElem = noiseElem.getElements();

        for (MetadataElement dataSetListElem : noiseDataSetListElem) {
            final MetadataElement noiElem = dataSetListElem.getElement("noise");
            final MetadataElement adsHeaderElem = noiElem.getElement("adsHeader");
            final String pol = adsHeaderElem.getAttributeString("polarisation");
            if (coPolarization.contains(pol)) {
                final MetadataElement noiseVectorListElem = noiElem.getElement("noiseVectorList");
                final int count = Integer.parseInt(noiseVectorListElem.getAttributeString("count"));
                Sentinel1Utils.NoiseVector[] noiseVectorList = Sentinel1Utils.getNoiseVector(noiseVectorListElem);
                noiseVector = noiseVectorList[count/2];
                break;
            }
        }

        if (noiseVector == null) {
            throw new OperatorException("Input product does not have noise vector for HH or VV band");
        }
    }

    /**
     * Compute noise LUTs for pixels of a whole range line for given noise vector.
     */
    private void computeNoiseLUT() {

        try {
            noiseLUT = new double[sourceImageWidth];

            if (!thermalNoiseCorrectionPerformed) {
                int pixelIdx = getPixelIndex(0, noiseVector);
                final int maxLength = noiseVector.pixels.length - 2;
                for (int x = 0; x < sourceImageWidth; x++) {

                    if (x > noiseVector.pixels[pixelIdx + 1] && pixelIdx < maxLength) {
                        pixelIdx++;
                    }

                    final int xx0 = noiseVector.pixels[pixelIdx];
                    final int xx1 = noiseVector.pixels[pixelIdx + 1];
                    final double muX = (double) (x - xx0) / (double) (xx1 - xx0);

                    noiseLUT[x] = Maths.interpolationLinear(
                            noiseVector.noiseLUT[pixelIdx], noiseVector.noiseLUT[pixelIdx + 1], muX)*scalingFactor;
                }
            } else {
                for (int x = 0; x < sourceImageWidth; x++) {
                    noiseLUT[x] = 0.0;
                }
            }
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException("computeNoiseLUT", e);
        }
    }

    /**
     * Get pixel index in a given noise vector for a given pixel.
     *
     * @param x           Pixel coordinate.
     * @param noiseVector Noise vector.
     * @return The pixel index.
     */
    private static int getPixelIndex(final int x, final Sentinel1Utils.NoiseVector noiseVector) {

        for (int i = 0; i < noiseVector.pixels.length; i++) {
            if (x < noiseVector.pixels[i]) {
                return i - 1;
            }
        }
        return noiseVector.pixels.length - 2;
    }

    /**
     * Compute noise scaling factor.
     */
    private void computeNoiseScalingFactor() {

        if (version < 2.50) {
            final String acquisitionMode = absRoot.getAttributeString(AbstractMetadata.ACQUISITION_MODE);
            double knoise;
            if (acquisitionMode.contains("IW")) {
                knoise = 75088.7;
            } else if (acquisitionMode.contains("EW")) {
                knoise = 56065.87;
            } else {
                throw new OperatorException("Cannot apply the operator to the input GRD product.");
            }

            final double dn0 = getDN0();

            if (version < 2.34) {
                scalingFactor = knoise*dn0;
            } else {
                scalingFactor = knoise*dn0*dn0;
            }

        } else {
            scalingFactor = 1.0;
        }
    }

    /**
     * Get the first element in DN vector from the original product metadata.
     */
    private double getDN0() {

        String[] selectedPols = {coPolarization};
        Sentinel1Calibrator.CalibrationInfo[] calibration = Sentinel1Calibrator.getCalibrationVectors(
                sourceProduct, Arrays.asList(selectedPols), false, false, false, true);

        for (Sentinel1Calibrator.CalibrationInfo cal : calibration) {
            if (cal.polarization.contains(coPolarization)) {

                final float[] dnLUT = Sentinel1Calibrator.getVector(
                        Sentinel1Calibrator.CALTYPE.DN, cal.getCalibrationVector(0));

                return dnLUT[0];
            }
        }

        return 0.0;
    }

    /**
     * Create a target product for output.
     */
    private void createTargetProduct() {

        targetProduct = new Product(sourceProduct.getName(),
                sourceProduct.getProductType(),
                sourceImageWidth,
                                    sourceImageHeight);

        addSelectedBands();

        ProductUtils.copyProductNodes(sourceProduct, targetProduct);
    }

    /**
     * Add user selected bands to target product.
     */
    private void addSelectedBands() {

        final Band[] sourceBands = sourceProduct.getBands();
        for (Band srcBand : sourceBands) {

            final String unit = srcBand.getUnit();
            if (unit == null) {
                throw new OperatorException("band " + srcBand.getName() + " requires a unit");
            }

            if (!unit.contains(Unit.AMPLITUDE) && !unit.contains(Unit.INTENSITY)) {
                continue;
            }

            final String srcBandName = srcBand.getName();
            if (selectedPolarisations != null && selectedPolarisations.length != 0 &&
                    !containSelectedPolarisations(srcBandName)) {
                continue;
            }

            if (srcBand instanceof VirtualBand) {

                final VirtualBand sourceBand = (VirtualBand) srcBand;

                final VirtualBand targetBand = new VirtualBand(
                        srcBandName,
                        sourceBand.getDataType(),
                        sourceBand.getRasterWidth(),
                        sourceBand.getRasterHeight(),
                        sourceBand.getExpression());

                ProductUtils.copyRasterDataNodeProperties(sourceBand, targetBand);
                targetProduct.addBand(targetBand);

            } else {

                final Band targetBand = new Band(
                        srcBandName,
                        srcBand.getDataType(),
                        srcBand.getRasterWidth(),
                        srcBand.getRasterHeight());

                targetBand.setUnit(srcBand.getUnit());
                targetBand.setNoDataValue(srcBand.getNoDataValue());
                targetBand.setNoDataValueUsed(srcBand.isNoDataValueUsed());
                targetBand.setDescription(srcBand.getDescription());
                targetProduct.addBand(targetBand);
            }
        }
    }

    private boolean containSelectedPolarisations(final String bandName) {
        for (String pol : selectedPolarisations) {
            if (bandName.contains(pol)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Called by the framework in order to compute a tile for the given target band.
     * <p>The default implementation throws a runtime exception with the message "not implemented".</p>
     *
     * @param targetTileMap   The target tiles associated with all target bands to be computed.
     * @param targetRectangle The rectangle of target tile.
     * @param pm              A progress monitor which should be used to determine computation cancelation requests.
     * @throws OperatorException
     *          If an error occurs during computation of the target raster.
     */
    @Override
    public void computeTileStack(Map<Band, Tile> targetTileMap, Rectangle targetRectangle, ProgressMonitor pm)
            throws OperatorException {

        try {
            final int x0 = targetRectangle.x;
            final int y0 = targetRectangle.y;
            final int w = targetRectangle.width;
            final int h = targetRectangle.height;
            final int yMax = y0 + h;
            final int xMax = x0 + w;
            //System.out.println("x0 = " + x0 + ", y0 = " + y0 + ", w = " + w + ", h = " + h);

            final Set<Band> keySet = targetTileMap.keySet();
            final int numBands = keySet.size();
            final ProductData[] targetData = new ProductData[numBands];
            final ProductData[] sourceData = new ProductData[numBands];
            final Tile[] sourceTile = new Tile[numBands];
            final Tile[] targetTile = new Tile[numBands];
            final double[] bandNoDataValues = new double[numBands];

            int k = 0;
            for (Band targetBand : keySet) {
                targetTile[k] = targetTileMap.get(targetBand);
                targetData[k] = targetTile[k].getDataBuffer();

                final Band srcBand = sourceProduct.getBand(targetBand.getName());
                sourceTile[k] = getSourceTile(srcBand, targetRectangle);
                sourceData[k] = sourceTile[k].getDataBuffer();
                bandNoDataValues[k] = srcBand.getNoDataValue();
                k++;
            }

            final TileIndex srcIndex = new TileIndex(sourceTile[0]);
            final TileIndex tgtIndex = new TileIndex(targetTile[0]);

            final Tile coPolTile = getSourceTile(coPolBand, targetRectangle);
            final ProductData coPolData = coPolTile.getDataBuffer();

            double coPolDataValue, deNoisedDataValue;
            for (int y = y0; y < yMax; y++) {
                srcIndex.calculateStride(y);
                tgtIndex.calculateStride(y);

                for (int x = x0; x < xMax; x++) {
                    final int srcIdx = srcIndex.getIndex(x);

                    boolean testPixel = x < borderLimit || x > sourceImageWidth-borderLimit ||
                                        y < borderLimit || y > sourceImageHeight-borderLimit;

                    if (testPixel){
                        coPolDataValue = coPolData.getElemDoubleAt(srcIdx);
                        if (coPolDataValue == noDataValue) {
                            continue;
                        }

                        if(coPolDataValue < 30) {
                            deNoisedDataValue = 0;
                        } else {
                            deNoisedDataValue =
                                    Math.sqrt(Math.max(coPolDataValue * coPolDataValue - noiseLUT[x], 0.0));
                        }

                        if (deNoisedDataValue < trimThreshold) {
                            final int tgtIdx = tgtIndex.getIndex(x);
                            for (int i = 0; i < numBands; i++) {
                                targetData[i].setElemDoubleAt(tgtIdx, bandNoDataValues[i]);
                            }
                        } else {
                            testPixel = false;
                        }
                    }
                    if(!testPixel) {
                        final int tgtIdx = tgtIndex.getIndex(x);
                        for (int i = 0; i < numBands; i++) {
                            targetData[i].setElemDoubleAt(tgtIdx, sourceData[i].getElemDoubleAt(srcIdx));
                        }
                    }
                }
            }

        } catch (Throwable e) {
            throw new OperatorException(e.getMessage());
        }
    }


    /**
     * The SPI is used to register this operator in the graph processing framework
     * via the SPI configuration file
     * {@code META-INF/services/OperatorSpi}.
     * This class may also serve as a factory for new operator instances.
     *
     * @see OperatorSpi#createOperator()
     * @see OperatorSpi#createOperator(java.util.Map, java.util.Map)
     */
    public static class Spi extends OperatorSpi {
        public Spi() {
            super(RemoveGRDBorderNoiseOp.class);
        }
    }
}
