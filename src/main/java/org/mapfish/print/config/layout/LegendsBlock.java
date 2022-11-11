/*
 * Copyright (C) 2013  Camptocamp
 *
 * This file is part of MapFish Print
 *
 * MapFish Print is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * MapFish Print is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with MapFish Print.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.mapfish.print.config.layout;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import org.mapfish.print.InvalidValueException;
import org.mapfish.print.PDFUtils;
import org.mapfish.print.RenderingContext;
import org.mapfish.print.legend.LegendItemTable;
import org.mapfish.print.utils.PJsonArray;
import org.mapfish.print.utils.PJsonObject;

import com.itextpdf.text.Chunk;
import com.itextpdf.text.Document;
import com.itextpdf.text.DocumentException;
import com.itextpdf.text.Element;
import com.itextpdf.text.Font;
import com.itextpdf.text.FontFactory;
import com.itextpdf.text.Image;
import com.itextpdf.text.Phrase;
import com.itextpdf.text.pdf.BaseFont;
import com.itextpdf.text.pdf.PdfContentByte;
import com.itextpdf.text.pdf.PdfPCell;
import com.itextpdf.text.pdf.PdfPTable;
import com.itextpdf.text.pdf.PdfTemplate;
import com.itextpdf.text.pdf.PdfWriter;

/**
 * Bean to configure a !legends block.
 * <p/>
 * See http://trac.mapfish.org/trac/mapfish/wiki/PrintModuleServer#Legendsblock
 */
public class LegendsBlock extends Block {

    public static final Logger LOGGER = LogManager.getLogger(LegendsBlock.class);

    private static String tempDir = System.getProperty("java.io.tmpdir");

    private static String fileSeparator = System.getProperty("file.separator");

    private boolean borders = false; // for debugging or seeing effects

    private float maxWidth = Float.MAX_VALUE; // so setting max value!
    // multi column is always enabled when maxHeight is set to something
    // lower than the page size/height

    private float maxHeight = Float.MAX_VALUE;

    private float iconMaxWidth = Float.MAX_VALUE; // MAX_VALUE/0 means disable

    private float iconMaxHeight = 8; // 0 means disable

    private float iconPadding[] = { 0f, 0f, 0f, 0f };

    private float textMaxWidth = Float.MAX_VALUE;

    // private float textMaxHeight = Float.MAX_VALUE; // UNUSED for now!
    private float textPadding[] = { 0f, 0f, 0f, 0f };

    private float scale = 1f; // 1 means disable

    private boolean inline = true;

    private float classIndentation = 20;

    private float layerSpaceBefore = 5;

    private float layerSpace = 5;

    private float classSpace = 2;

    private String layerFont = "Helvetica";

    protected float layerFontSize = 10;

    private String classFont = "Helvetica";

    protected float classFontSize = 8;

    private String fontEncoding = BaseFont.WINANSI;

    private int horizontalAlignment = Element.ALIGN_CENTER;

    private float[] columnPadding = { 0f, 0f, 0f, 0f };

    private int maxColumns = Integer.MAX_VALUE;

    private float fitWidth = 0.0f;

    private float fitHeight = 0.0f;

    private boolean failOnBrokenUrl = true;

    private boolean dontBreakItems = false;

    private boolean reorderColumns = false;

    private boolean overflow = false;

    private List<PdfPTable> extraColumns = new ArrayList<PdfPTable>();

    public boolean hasExtraRendering() {
        return extraColumns.size() > 0;
    }

    /**
     * Render the legends block
     *
     * @param params
     * @param target
     * @param context
     * @throws com.lowagie.text.DocumentException
     * @see org.mapfish.print.config.layout.Block#render( org.mapfish.print.utils.PJsonObject, org.mapfish.print.config.layout.Block.PdfElement,
     *      org.mapfish.print.RenderingContext)
     */
    @Override
    public void render(PJsonObject params, PdfElement target, RenderingContext context)
            throws DocumentException {
        Renderer renderer = new Renderer(params, context);
        renderer.render(target);
    }

    /**
     * A renderer to render the legend block
     *
     * @author Stéphane Brunner
     */
    private class Renderer {
        /*
         * need these to calculate widths/heights of output
         *
         * For example a cell could contain the text "Hello World" while it is the equivalent of 7 characters wide "World" would be wrapped onto the
         * next line which would make the height calculation complicated if not actually rendered onto a page. This is important for long legend texts
         * which wrap.
         */

        private String tempFilename;

        private Document tempDocument = new Document();

        private PdfWriter writer;

        private RenderingContext context;

        // all the pdf columns
        private ArrayList<PdfPTable> columns = new ArrayList<PdfPTable>();

        // all the columns width
        private final ArrayList<Float> columnsWidth = new ArrayList<Float>();

        // the current column
        private PdfPTable column;

        // the current column height
        private int currentColumnIndex = 0;

        private float maxActualImageWidth = 0;

        private float maxActualTextWidth = 0;

        private final ArrayList<LegendItemTable> legendItems = new ArrayList<LegendItemTable>();

        // optimum widths are used to compute the best possible widths of legend
        // items
        private float optimumIconCellWidth = 0f;

        private float optimumTextCellWidth = 0f;

        // temporary cells used in calculations
        private PdfPCell leftCell;

        private PdfPCell rightCell;

        private float[] absoluteWidths;

        private boolean needTempDocument = true;

        private final HashMap<Integer, Float> subHeights = new HashMap<Integer, Float>();

        /**
         * Construct
         *
         * @param params the params
         * @param context the context
         */
        public Renderer(PJsonObject params, RenderingContext context) throws DocumentException {
            column = getDefaultOuterTable(1);
            columns.add(column);
            this.context = context;
            PJsonArray legends = context.getGlobalParams().optJSONArray("legends");
            if (legends == null || legends.size() == 0 || (overflow && hasExtraRendering())) {
                // this prevents a bug when there are no legends
                needTempDocument = false;
            }
            if (needTempDocument) {
                makeTempDocument(); // need this to calculate widths and heights of elements!
            }
        }

        public void render(PdfElement target) throws DocumentException {
            // float optimumTextWidthWithoutIcon = 0f;
            int numColumns = 1;
            boolean overflowDone = false;
            if (overflow && hasExtraRendering()) {
                int extraColumnsUsed = extraColumns.size() > maxColumns ? maxColumns
                        : extraColumns.size();
                columns.clear();
                for (int count = 0; count < extraColumnsUsed; count++) {
                    columns.add(extraColumns.remove(0));
                }
                overflowDone = true;
            } else {
                absoluteWidths = new float[1];

                // create the legend
                PJsonArray legends = context.getGlobalParams().optJSONArray("legends");
                float maxColumnWidth = maxWidth;

                if (legends != null && legends.size() > 0) {
                    for (int i = 0; i < legends.size(); ++i) {
                        try {
                            createLegend(legends.getJSONObject(i), i == 0);
                        } catch (org.mapfish.print.InvalidValueException e) {
                            if (failOnBrokenUrl) {
                                throw e;
                            } else {
                                // error getting legend icon
                                // we don't fail, we simply skip this item
                                LOGGER.warn("Error getting legend item "
                                        + legends.getJSONObject(i).getString("name"), e);
                            }
                        }
                    }
                    setOptimumCellWidths(maxColumnWidth);
                    setOptimumColumns(maxColumnWidth, reorderColumns);
                }
            }

            numColumns = columns.size();

            int finalTableColumns = numColumns > maxColumns ? maxColumns : numColumns;
            PdfPTable table = getDefaultOuterTable(finalTableColumns);
            if (maxWidth != Float.MAX_VALUE) {
                table.setTotalWidth(maxWidth);
            }
            if (overflow && columns.size() > finalTableColumns && !overflowDone) {
                for (int count = finalTableColumns; count < columns.size(); count++) {
                    extraColumns.add(columns.get(count));
                }
                columns.removeAll(extraColumns);
                context.getExtraPages().add(ExtraPage.createAfter(context.getCurrentPosition(),
                        LegendsBlock.this, context.getCurrentPage()));
            }
            for (PdfPTable col : columns) {
                PdfPCell cell = new PdfPCell(col);
                cell.setPaddingTop(columnPadding[0]);
                cell.setPaddingRight(columnPadding[1]);
                cell.setPaddingBottom(columnPadding[2]);
                cell.setPaddingLeft(columnPadding[3]);
                if (!borders) {
                    cell.setBorder(PdfPCell.NO_BORDER);
                }
                table.addCell(cell);
            }
            if (!overflow && (numColumns > maxColumns && numColumns % maxColumns != 0)) {
                // add filler columns
                for (int count = 0; count < (maxColumns - numColumns % maxColumns); count++) {
                    PdfPCell cell = new PdfPCell(getDefaultOuterTable(1));
                    if (!borders) {
                        cell.setBorder(PdfPCell.NO_BORDER);
                    }
                    table.addCell(cell);
                }
            }
            if (maxWidth < Float.MAX_VALUE) {
                table.setTotalWidth(maxWidth);
                table.setLockedWidth(true);
            }
            table.setHorizontalAlignment(horizontalAlignment);
            if (fitWidth != 0.0f || fitHeight != 0.0) {
                tempDocument.add(table);
                float aspectRatio = table.getTotalWidth() / table.getTotalHeight();
                if (fitWidth == 0.0f) {
                    fitWidth = aspectRatio * fitHeight;
                }
                if (fitHeight == 0.0f) {
                    fitHeight = fitWidth / aspectRatio;
                }
                PdfContentByte canvas = context.getDirectContent();
                PdfTemplate template = canvas.createTemplate(table.getTotalWidth(),
                        table.getTotalHeight());
                table.writeSelectedRows(0, -1, 0, table.getTotalHeight(), template);
                Image img = Image.getInstance(template);
                img.scaleToFit(fitWidth, fitHeight);
                // img.setAbsolutePosition(0, 0);

                target.add(new Chunk(img, 0f, 0f, true));
            } else {
                target.add(table);
            }
            if (!overflowDone) {
                cleanup(); // don't forget to cleanup afterwards
            }
        }

        /**
         * Inner class to save and restore a {@link LegendsBlock#setOptimumColumns(float, boolean)} iteration state
         */
        private class SetOptimumCellColumnsParameters {
            float totalHeight;

            float spacingBefore;

            float cellPaddingTop;

            float maxColumnWidth;

            int index;

            int columnsSize;

            public SetOptimumCellColumnsParameters(float totalHeight, float spacingBefore,
                    float cellPaddingTop, float maxColumnWidth, int index, int columnsSize) {
                this.totalHeight = totalHeight;
                this.spacingBefore = spacingBefore;
                this.cellPaddingTop = cellPaddingTop;
                this.maxColumnWidth = maxColumnWidth;
                this.index = index;
                this.columnsSize = columnsSize;
            }

        }

        /**
         * Put each legend item inside the legendItems array to the correct column
         * 
         * @param maxColumnWidth previous max column width (maybe change if we need to change the number of columns)
         * @param reorderInColumns when this flag it's true, it try to reorder the legends block in columns to obtain a uniform view (try to fill all
         *        columns before create a new one)
         * 
         * @throws DocumentException
         */
        private void setOptimumColumns(float maxColumnWidth, boolean reorderInColumns)
                throws DocumentException {
            int maxColumnsToUse = maxColumns;
            if (overflow) {
                maxColumnsToUse = Integer.MAX_VALUE;
            }
            if (legendItems.size() == 0 || maxColumnsToUse == 1) {
                // we can't reorder nothing
                reorderInColumns = false;
            }

            if (reorderInColumns) {
                reorderColumns(maxColumnWidth);
            } else {
                // Don't reorder in columns (old implementation)
                float totalHeight = 0f;
                for (int i = 0, len = legendItems.size(); i < len; ++i) {
                    LegendItemTable legendItem = legendItems.get(i);
                    /**
                     * need the padding set before in createLegend and add it to the optimum absolute widths
                     */
                    computeOptimumLegendItemWidths(legendItem);

                    totalHeight += getHeight(legendItem);
                    float cellPaddingTop = leftCell.getPaddingTop();
                    float spacingBefore = legendItem.getSpaceBefore();
                    if (totalHeight > maxHeight && i > 0) {
                        column = getDefaultOuterTable(1);
                        columns.add(column);
                        totalHeight = getHeight(legendItem);
                        /**
                         * This fixes the case where a layer legend item gets too much padding from the top.
                         */
                        if (spacingBefore > 0f && cellPaddingTop > 0) {
                            leftCell.setPaddingTop(cellPaddingTop - spacingBefore);
                            if (rightCell != null) {
                                rightCell.setPaddingTop(rightCell.getPaddingTop() - spacingBefore);
                            }
                        }
                        int columnsSize = columns.size();
                        if (maxColumns > 1) {
	                        maxColumnWidth = (maxWidth / (columnsSize > maxColumnsToUse ? maxColumnsToUse : columnsSize)) -
	                                columnPadding[1] - columnPadding[3];
	                        if (maxColumnWidth < optimumIconCellWidth +
	                                optimumTextCellWidth) {
	                            /**
	                             * clear out the table and start new, because the
	                             * maxColumnWidth has changed!
	                             */
	                            column = getDefaultOuterTable(1);
	                            columns = new ArrayList<PdfPTable>(columnsSize);
	                            columns.add(column);
	                            i = -1;
	                            setOptimumCellWidths(maxColumnWidth);
	                        } else {
	                            column.addCell(legendItem);
	                        }
                        } else {
                        	column.addCell(legendItem);
                        }
                    } else {
                        column.addCell(legendItem);
                    }
                }
                column.setHorizontalAlignment(horizontalAlignment);
            }
        }

        private void reorderColumns(float maxColumnWidth) throws DocumentException {

            int maxColumnsToUse = maxColumns;
            if (overflow) {
                maxColumnsToUse = Integer.MAX_VALUE;
            }

            // first, let's calculate heights for each legend item
            final Map<LegendItemTable, Float> itemHeights = new HashMap<LegendItemTable, Float>();
            for (int i = 0, len = legendItems.size(); i < len; ++i) {
                LegendItemTable legendItem = legendItems.get(i);
                // is this needed??
                computeOptimumLegendItemWidths(legendItem);

                float itemHeight = getHeight(legendItem);
                itemHeights.put(legendItem, itemHeight);
            }

            // then reorder items by descending height
            TreeSet<LegendItemTable> orderedItems = new TreeSet<LegendItemTable>(
                    new Comparator<LegendItemTable>() {

                        @Override
                        public int compare(LegendItemTable item1, LegendItemTable item2) {
                            int compareResult = itemHeights.get(item2)
                                    .compareTo(itemHeights.get(item1));
                            if (compareResult == 0) {
                                compareResult = 1;
                            }
                            return compareResult;
                        }

                    });
            orderedItems.addAll(legendItems);

            int maxFinalColumns = maxColumnsToUse > 0
                    ? Math.min(maxColumnsToUse, legendItems.size())
                    : legendItems.size();

            List<List<LegendItemTable>> candidateColumns = new ArrayList<List<LegendItemTable>>();
            for (int count = 0; count < maxFinalColumns; count++) {
                candidateColumns.add(new ArrayList<LegendItemTable>());
            }
            float[] occupiedSpace = new float[maxFinalColumns];
            for (int count = 0; count < maxFinalColumns; count++) {
                occupiedSpace[count] = 0.0f;
            }
            List<LegendItemTable> notFitted = new ArrayList<LegendItemTable>();
            // use first fit with descending order alghoritm to allocate items to columns
            // (http://en.wikipedia.org/wiki/Bin_packing_problem)
            for (LegendItemTable itemTable : orderedItems) {
                boolean inserted = false;
                for (int count = 0; count < maxFinalColumns; count++) {
                    float height = occupiedSpace[count];
                    Float itemHeight = itemHeights.get(itemTable);
                    if (!inserted && (height + itemHeight <= maxHeight)) {
                        inserted = true;
                        occupiedSpace[count] += itemHeight;
                        candidateColumns.get(count).add(itemTable);
                    }
                }
                // throw apart not-fitted items for later processing
                if (!inserted) {
                    notFitted.add(itemTable);
                }
            }
            for (int count = 0; count < maxFinalColumns; count++) {
                if (occupiedSpace[count] > 0) {
                    List<LegendItemTable> items = candidateColumns.get(count);
                    if (count > 0) { // fist column already generated
                        column = getDefaultOuterTable(1);
                    }
                    // column = columns.get(index);
                    for (LegendItemTable item : items) {
                        column.addCell(item);
                        column.setHorizontalAlignment(horizontalAlignment);
                    }
                    if (count > 0) { // fist column already added
                        columns.add(column);
                    }
                }
            }
            for (LegendItemTable item : notFitted) {
                if (itemHeights.get(item) > maxHeight) {
                    column = getDefaultOuterTable(1);
                }
                PdfPCell cell = new PdfPCell(item);
                column.addCell(item);
                column.setHorizontalAlignment(horizontalAlignment);
                if (itemHeights.get(item) > maxHeight) {
                    columns.add(column);
                }
            }
        }
        /*
         * private void reorderColumns(float maxColumnWidth) throws DocumentException { // array to save the actual column height int maxFinalColumns
         * = maxColumns > 0 ? Math.min(maxColumns, legendItems.size()): legendItems.size(); float[] columnsHeight = new float[maxFinalColumns];
         * 
         * // first pass. we're going to save inside targetColumns the column items by column index Map<Integer, ArrayList<LegendItemTable>>
         * targetColumns = new HashMap<Integer, ArrayList<LegendItemTable>>();
         * 
         * float totalHeight = 0f; int lastColumnIndex = 0; // last column added index for columnsHeight array int columnsSize = 1; for (int i = 0,
         * len = legendItems.size(); i < len; ++i) { LegendItemTable legendItem = legendItems.get(i);
         * 
         * computeOptimumLegendItemWidths(legendItem);
         * 
         * float itemHeight = getHeight(legendItem); totalHeight += itemHeight; float cellPaddingTop = leftCell.getPaddingTop(); float spacingBefore =
         * legendItem.getSpaceBefore();
         * 
         * SetOptimumCellColumnsParameters parameters = new SetOptimumCellColumnsParameters(totalHeight, spacingBefore, cellPaddingTop,
         * maxColumnWidth, i, columnsSize); Integer columnIndex = getColumnToUse(itemHeight, columnsHeight); if(columnIndex > -1 // need a new column
         * && columnIndex <= lastColumnIndex){ // known column ArrayList<LegendItemTable> column = targetColumns.get(columnIndex); if(column == null){
         * column = new ArrayList<LegendItemTable>(); } column.add(legendItem); targetColumns.put(columnIndex, column); columnsHeight[columnIndex] +=
         * itemHeight; // add the height to the column }else{ parameters = computeNewParameters(legendItem, parameters); i = parameters.index;
         * totalHeight = parameters.totalHeight; columnsSize = parameters.columnsSize; if(parameters.maxColumnWidth != maxColumnWidth){
         * 
         * maxColumnWidth = parameters.maxColumnWidth; // clear optimization parameters, we need to restart in for statement columnsHeight = new
         * float[maxFinalColumns]; lastColumnIndex = 0; targetColumns = new HashMap<Integer, ArrayList<LegendItemTable>>(); i= -1; }else{
         * columnsHeight[++lastColumnIndex] += itemHeight; // new column ArrayList<LegendItemTable> column = new ArrayList<LegendItemTable>();
         * column.add(legendItem); targetColumns.put(lastColumnIndex, column); } } }
         * 
         * //now only iterate an add each legend item to the correct column for(Integer index: targetColumns.keySet()){ if(index > 0){ //fist column
         * already generated column = getDefaultOuterTable(1); } //column = columns.get(index); for(LegendItemTable item: targetColumns.get(index)){
         * column.addCell(item); column.setHorizontalAlignment(horizontalAlignment); } if(index > 0){ //fist column already added columns.add(column);
         * } } }
         */

        /**
         * Obtain the index of the column to add the legend item
         * 
         * @param itemHeight
         * @param columnsHeight
         * 
         * @return -1 if you can't add in any column or column index otherwise
         */
        private int getColumnToUse(float itemHeight, float[] columnsHeight) {
            int columnIndex = -1; // new one
            // if all columns are fill, we need to select the smaller one
            boolean allFill = false;
            int alternativeIndex = 0;
            if (columnsHeight[0] == 0f) {
                columnIndex = 0;
            } else {
                float smallerColumn = Float.MAX_VALUE;
                for (int i = 0; i < columnsHeight.length; i++) {
                    float newHeight = columnsHeight[i] + itemHeight;
                    if (columnsHeight[i] == 0) {
                        break; // the column is empty. must be already found and we need to create a new one
                    } else if (columnsHeight[i] != 0 && newHeight < maxHeight
                            && newHeight < smallerColumn) {
                        smallerColumn = newHeight;
                        columnIndex = i;
                    } else if (newHeight < smallerColumn) {
                        smallerColumn = newHeight;
                        alternativeIndex = i;
                    }
                    if ((i == (columnsHeight.length - 1)) && (columnIndex == -1)) {
                        allFill = true;
                    }
                }
            }
            return allFill ? alternativeIndex : columnIndex;
        }

        /**
         * Recalculate legends block parameters when a new legend item is added
         * 
         * @param legendItem to ad
         * @param parameters previous parameters
         * 
         * @return SetOptimumCellColumnsParameters calculated
         * 
         * @throws DocumentException
         */
        private SetOptimumCellColumnsParameters computeNewParameters(LegendItemTable legendItem,
                SetOptimumCellColumnsParameters parameters) throws DocumentException {
            parameters.totalHeight = getHeight(legendItem);
            parameters.columnsSize++;
            /**
             * This fixes the case where a layer legend item gets too much padding from the top.
             */
            if (parameters.spacingBefore > 0f && parameters.cellPaddingTop > 0) {
                leftCell.setPaddingTop(parameters.cellPaddingTop - parameters.spacingBefore);
                if (rightCell != null) {
                    rightCell.setPaddingTop(rightCell.getPaddingTop() - parameters.spacingBefore);
                }
            }
            int columnsSize = parameters.columnsSize;
            parameters.maxColumnWidth = (maxWidth
                    / (columnsSize > maxColumns ? maxColumns : columnsSize)) - columnPadding[1]
                    - columnPadding[3];
            if (parameters.maxColumnWidth < optimumIconCellWidth + optimumTextCellWidth) {
                parameters.index = -1;
                parameters.columnsSize = 1; // restart!!
                setOptimumCellWidths(parameters.maxColumnWidth);
            }
            return parameters;
        }

        /**
         * get width of text on the page with font
         * 
         * @param myString any string printed on the page
         * @param pdfFont Font needed to calculate this
         * @return width in points
         */
        private float getTextWidth(String myString, Font pdfFont) {
            BaseFont baseFont = pdfFont.getBaseFont();
            float width = baseFont == null ? new Chunk(myString).getWidthPoint()
                    : baseFont.getWidthPoint(myString, pdfFont.getSize());
            return width;
        }

        /**
         * Create a chunk from an image (svg, png, ...)
         *
         * @param context PDF rendering context
         * @param iconItem URL of the image
         * @param maxIconWidth width of the chunk
         * @param maxIconHeight height of the chunk
         * @return Chunk with image in it
         * @throws DocumentException
         */
        private Chunk createImageChunk(RenderingContext context, String iconItem,
                float maxIconWidth, float maxIconHeight, float scale) throws DocumentException {
            Chunk iconChunk = null;
            try {
                if (iconItem.indexOf("image%2Fsvg%2Bxml") != -1) { // TODO: make this cleaner
                    iconChunk = PDFUtils.createImageChunkFromSVG(context, iconItem, maxIconWidth,
                            maxIconHeight, scale);
                } else {
                    iconChunk = PDFUtils.createImageChunk(context, maxIconWidth, maxIconHeight,
                            scale, URI.create(iconItem), 0f);
                }
            } catch (IOException e) {
                throw new DocumentException(e);
            }
            return iconChunk;
        }

        /**
         * creates a "real" PDF document to draw on to be able to calculate correct widths of text etc
         *
         * @throws DocumentException
         */
        private void makeTempDocument() throws DocumentException {
            try {
                tempFilename = tempDir.indexOf('/') != -1 ? "" : "\\";
                long time = (new Date()).getTime();
                tempFilename = tempDir + fileSeparator + "mapfish-print-tmp-" + time + ".pdf";
                // Unfortunately have to open an actual file on disk
                // for the calculations to work properly
                writer = PdfWriter.getInstance(tempDocument, new FileOutputStream(tempFilename));
                tempDocument.open();
            } catch (FileNotFoundException e) {
                throw new DocumentException(e);
            } catch (DocumentException e) {
                // don't forget to delete the useless file
                new File(tempFilename).delete();
                throw new DocumentException(e);
            }
        }

        /**
         * delete the temporary file needed for dimensions' calculations
         *
         * @throws DocumentException
         */
        private void cleanup() throws DocumentException {
            if (!needTempDocument) {
                return;
            }
            try {
                tempDocument.close();
                writer.close();
                // don't forget to delete the useless file
            } catch (Exception e) {
                throw new DocumentException(e);
            } finally {
                new File(tempFilename).delete();
            }
        }

        /**
         * get the height in points when printed onto the temporary document
         *
         * @param element any PDF element
         * @return height in points
         * @throws DocumentException
         */
        private float getHeight(Element element) throws DocumentException {
            tempDocument.add(element);
            if (element instanceof PdfPTable) {
                return ((PdfPTable) element).getTotalHeight();
            }
            if (element instanceof PdfPCell) {
                return ((PdfPCell) element).getHeight();
            }
            return -1;
        }

        /**
         * create the legend for a layer json object
         *
         * @param layer JSON object
         * @param isFirst only do some things on the first item
         * @throws DocumentException
         */
        private void createLegend(PJsonObject layer, boolean isFirst) throws DocumentException {
            Font layerPdfFont = getLayerPdfFont();
            Font classPdfFont = getClassPdfFont();
            if (dontBreakItems) {
                // we may use only one table (main) and add the cells
                float spaceBefore = isFirst ? 0f : layerSpaceBefore;
                PdfPCell[] cells = createTableCells(0.0f, layer, layerPdfFont, layerSpace, true,
                        spaceBefore);
                LegendItemTable main = generateLegendItem(layer, spaceBefore, cells);
                main.completeRow();
                PJsonArray classes = layer.getJSONArray("classes");
                for (int j = 0; j < classes.size(); ++j) {
                    PJsonObject clazz = classes.getJSONObject(j);
                    cells = createTableCells(classIndentation, clazz, classPdfFont, classSpace,
                            inline, 0f);
                    addCells(main, cells);
                }
                legendItems.add(main);
            } else {
                LegendItemTable legendItemTable = createTableLine(0.0f, layer, layerPdfFont,
                        layerSpace, true, isFirst ? 0f : layerSpaceBefore);
                legendItems.add(legendItemTable);
                PJsonArray classes = layer.getJSONArray("classes");
                for (int j = 0; j < classes.size(); ++j) {
                    PJsonObject clazz = classes.getJSONObject(j);
                    legendItemTable = createTableLine(classIndentation, clazz, classPdfFont,
                            classSpace, inline, 0f);
                    legendItems.add(legendItemTable);
                }
            }
        }

        /**
         * Create a LegendItemTable with parameters
         * 
         * @param layer
         * @param spaceBefore
         * @param cells
         * 
         * @return
         * @throws DocumentException
         */
        private LegendItemTable generateLegendItem(PJsonObject layer, float spaceBefore,
                PdfPCell[] cells) throws DocumentException {
            // Parameters of the legend (the same that)
            String icon = layer.optString("icon"); // legend image
            String color = layer.optString("color");
            PJsonArray iconsArray = layer.optJSONArray("icons");
            int iconsSize = iconsArray == null ? 0 : iconsArray.size();
            boolean haveNoIcon = icon == null && iconsSize == 0;
            boolean iconBeforeName = layer.optBool("iconBeforeName", true);
            return generateLegendItem(haveNoIcon, color, spaceBefore, iconBeforeName, cells);
        }

        /**
         * Create a LegendItemTable with parameters
         * 
         * @param haveNoIcon
         * @param color
         * @param spaceBefore
         * @param iconBeforeName
         * @param cells
         * 
         * @return
         * 
         * @throws DocumentException
         */
        private LegendItemTable generateLegendItem(boolean haveNoIcon, String color,
                float spaceBefore, boolean iconBeforeName, PdfPCell[] cells)
                throws DocumentException {
            LegendItemTable legendItemTable;

            if (haveNoIcon && color == null) {
                legendItemTable = new LegendItemTable(1);
            } else {
                legendItemTable = new LegendItemTable(2);
            }
            legendItemTable.setIconBeforeName(iconBeforeName);
            legendItemTable.setTotalWidth(absoluteWidths);
            legendItemTable.getDefaultCell().setPadding(0f);
            legendItemTable.setSpaceBefore(spaceBefore);
            addCells(legendItemTable, cells);

            return legendItemTable;
        }

        /**
         * Create a item with the parameters
         * 
         * @param indent
         * @param node
         * @param pdfFont
         * @param lineSpace
         * @param defaultIconBeforeName
         * @param spaceBefore
         * 
         * @return
         * 
         * @throws DocumentException
         */
        private LegendItemTable createTableLine(float indent, PJsonObject node, Font pdfFont,
                float lineSpace, boolean defaultIconBeforeName, float spaceBefore)
                throws DocumentException {
            final String icon = node.optString("icon"); // legend image
            final String color = node.optString("color");
            final PJsonArray iconsArray = node.optJSONArray("icons");
            final int iconsSize = iconsArray == null ? 0 : iconsArray.size();
            final boolean haveNoIcon = icon == null && iconsSize == 0;
            // final PJsonArray icons = node.optJSONArray("icons"); // UNUSED, please check what this should be doing!
            boolean iconBeforeName = node.optBool("iconBeforeName", defaultIconBeforeName);

            // create the cells
            PdfPCell[] cells = createTableCells(indent, node, pdfFont, lineSpace,
                    defaultIconBeforeName, spaceBefore);

            // add to the table
            LegendItemTable legendItemTable = generateLegendItem(haveNoIcon, color, spaceBefore,
                    iconBeforeName, cells);

            return legendItemTable;
        }

        /**
         * 
         * @param table
         * @param cells
         */
        private void addCells(PdfPTable table, PdfPCell[] cells) {
            if (table != null && cells != null) {
                for (int i = 0; i < cells.length; i++) {
                    table.addCell(cells[i]);
                }
            }
        }

        /**
         * Create the cells for a row or a table
         * 
         * @param indent
         * @param node
         * @param pdfFont
         * @param lineSpace
         * @param defaultIconBeforeName
         * @param spaceBefore
         * 
         * @return cells for the legend item
         * 
         * @throws DocumentException
         */
        private PdfPCell[] createTableCells(float indent, PJsonObject node, Font pdfFont,
                float lineSpace, boolean defaultIconBeforeName, float spaceBefore)
                throws DocumentException {
            final String name = node.getString("name"); // legend text
            final String icon = node.optString("icon"); // legend image
            final String color = node.optString("color");
            final PJsonArray iconsArray = node.optJSONArray("icons");
            final int iconsSize = iconsArray == null ? 0 : iconsArray.size();
            final String icons[] = new String[iconsSize];
            final boolean haveNoIcon = icon == null && iconsSize == 0;
            final String iconScaleString = node.optString("scale", "" + scale); // legend image
            final float iconScale = Float.parseFloat(iconScaleString);
            // final PJsonArray icons = node.optJSONArray("icons"); // UNUSED, please check what this should be doing!
            boolean iconBeforeName = node.optBool("iconBeforeName", defaultIconBeforeName);

            for (int i = -1; ++i < iconsSize;) {
                icons[i] = iconsArray.getString(i);
            }

            Phrase imagePhrase = new Phrase();
            Chunk iconChunk;
            float imageWidth = 0f;
            if (iconsSize > 0) {
                for (String myIcon : icons) {
                    iconChunk = createImageChunk(context, myIcon, iconMaxWidth, iconMaxHeight,
                            iconScale);
                    imagePhrase.add(iconChunk);
                    imageWidth += iconChunk.getImage().getPlainWidth() + iconPadding[1]
                            + iconPadding[3];
                }
            } else if (icon != null) {
                iconChunk = createImageChunk(context, icon, iconMaxWidth, iconMaxHeight, iconScale);
                imagePhrase.add(iconChunk);
                imageWidth = iconChunk.getImage().getPlainWidth() + iconPadding[1] + iconPadding[3];
            } else if (color != null) {
                int width = iconMaxWidth < Float.MAX_VALUE ? (int) iconMaxWidth : 10;
                int height = iconMaxHeight < Float.MAX_VALUE ? (int) iconMaxHeight : 10;
                BufferedImage buffered = new BufferedImage(width, height,
                        BufferedImage.TYPE_BYTE_INDEXED);
                Graphics2D g2d = buffered.createGraphics();
                g2d.setColor(Color.decode(color));
                g2d.fillRect(0, 0, width, height);
                g2d.dispose();
                try {
                    iconChunk = new Chunk(Image.getInstance(buffered, null, false), 0f, 0f, true);
                    imagePhrase.add(iconChunk);
                    imageWidth = iconChunk.getImage().getPlainWidth() + iconPadding[1]
                            + iconPadding[3];
                } catch (IOException e) {
                    throw new DocumentException(e);
                }
            } else {
                iconChunk = new Chunk("");
                imagePhrase.add(iconChunk);
            }

            Phrase namePhrase = new Phrase();
            namePhrase.setFont(pdfFont);
            namePhrase.add(name);

            float textWidth = getTextWidth(name, pdfFont) + textPadding[1] + textPadding[3];

            int columnsWidthSize = columnsWidth.size();
            float maxWidthF = textWidth + imageWidth; // total with of legend item
            if (columnsWidthSize <= currentColumnIndex) {// need to add
                columnsWidth.add(Math.min(maxWidth, maxWidthF));
            } else if (columnsWidthSize >= 1 && currentColumnIndex == 0) {
                // need to get the min of max
                maxWidthF = Math.max(columnsWidth.get(0), maxWidthF);
                columnsWidth.set(0, Math.min(maxWidth, maxWidthF));
            }

            absoluteWidths = null;
            if (haveNoIcon && color == null) {
                absoluteWidths = new float[1];
                absoluteWidths[0] = textMaxWidth + iconMaxWidth + iconPadding[1] + iconPadding[3]
                        + textPadding[1] + textPadding[3];
            } else {
                absoluteWidths = new float[2];
                absoluteWidths[0] = iconMaxWidth + iconPadding[1] + iconPadding[3];
                absoluteWidths[1] = textMaxWidth + textPadding[1] + textPadding[3];
            }

            PdfPCell imageCell = null;
            if (!haveNoIcon) {
                imageCell = new PdfPCell(imagePhrase);
                /**
                 * CSS like padding for icons: not to forget indent!
                 */
                float indentLeft = iconBeforeName ? indent : 0f;
                imageCell.setPaddingTop(spaceBefore + iconPadding[0]);
                imageCell.setPaddingRight(iconPadding[1]);
                imageCell.setPaddingBottom(lineSpace + iconPadding[2]);
                imageCell.setPaddingLeft(indentLeft + iconPadding[3]);

                if (!borders) {
                    imageCell.setBorder(PdfPCell.NO_BORDER);
                }

            }
            if (color != null) {
                imageCell = new PdfPCell(imagePhrase);
            }
            PdfPCell nameCell = new PdfPCell(namePhrase);

            /**
             * If there is no icon we need to add the left indent to the name column. Also if the icon is not before the text!
             */
            float indentLeft = haveNoIcon || !iconBeforeName ? (float) indent : 0f;

            /**
             * CSS like padding for text not to forget spacing!
             */
            nameCell.setPaddingTop(spaceBefore + textPadding[0]);
            nameCell.setPaddingRight(textPadding[1]);
            nameCell.setPaddingBottom(lineSpace + textPadding[2]);
            nameCell.setPaddingLeft(indentLeft + textPadding[3]);

            if (!iconBeforeName && inline) {
                nameCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
            }
            if (!borders) {
                nameCell.setBorder(PdfPCell.NO_BORDER);
            }

            if (!borders && color != null) {
                imageCell.setBorder(PdfPCell.NO_BORDER);
            }

            maxActualImageWidth = Math.max(imageWidth, maxActualImageWidth);
            maxActualTextWidth = Math.max(textWidth, maxActualTextWidth);

            PdfPCell[] cells = new PdfPCell[nameCell != null && imageCell != null ? 2 : 1];

            if (inline) {
                if (iconBeforeName) {
                    if (imageCell != null) {
                        cells[0] = imageCell;
                    }
                    cells[cells.length - 1] = nameCell;
                } else {
                    cells[0] = nameCell;
                    if (imageCell != null) {
                        cells[1] = imageCell;
                    }
                }
            } else {
                if (iconBeforeName) {
                    if (imageCell != null) {
                        cells[0] = imageCell;
                    }
                    cells[cells.length - 1] = nameCell;
                } else {
                    cells[0] = nameCell;
                    if (imageCell != null) {
                        cells[1] = imageCell;
                    }
                }
            }

            return cells;

            // return getHeight(legendItemTable);
        }

        private PdfPTable getDefaultOuterTable(int numColumns) {
            PdfPTable pdfPTable = new PdfPTable(numColumns);
            if (!borders) {
                pdfPTable.getDefaultCell().setBorder(PdfPCell.NO_BORDER);
            }
            pdfPTable.setWidthPercentage(100f);
            pdfPTable.getDefaultCell().setPadding(0f);
            pdfPTable.setSpacingAfter((float) spacingAfter);
            return pdfPTable;
        }

        private void computeOptimumLegendItemWidths(LegendItemTable legendItem)
                throws DocumentException {
            PdfPCell cells[];
            if (dontBreakItems) {
                // legend it's after first row
                cells = legendItem.getRow(1).getCells();
            } else {
                cells = legendItem.getRow(0).getCells();
            }
            int numCells = cells.length;
            leftCell = cells[0];
            rightCell = null;
            if (numCells > 1) {
                rightCell = cells[1];
            }
            if (numCells == 1) {
                absoluteWidths = new float[1];
                absoluteWidths[0] = optimumTextCellWidth + optimumIconCellWidth;
            } else {
                absoluteWidths = new float[2];
                if (legendItem.isIconBeforeName()) {
                    absoluteWidths[0] = optimumIconCellWidth;
                    absoluteWidths[1] = optimumTextCellWidth;
                } else {
                    absoluteWidths[0] = optimumTextCellWidth;
                    absoluteWidths[1] = optimumIconCellWidth;
                }
            }
            legendItem.setTotalWidth(absoluteWidths);
            legendItem.setLockedWidth(true);
            legendItem.setHorizontalAlignment(horizontalAlignment);
        }

        private void setOptimumCellWidths(float maxColumnWidth) {
            optimumIconCellWidth = Math.min(maxActualImageWidth + classIndentation,
                    iconMaxWidth + classIndentation);
            optimumTextCellWidth = Math.min(maxActualTextWidth, textMaxWidth);
            // don't let the icon cell be bigger than half
            optimumIconCellWidth = Math.min(optimumIconCellWidth, maxColumnWidth / 2);
            optimumTextCellWidth = Math.min(optimumTextCellWidth,
                    maxColumnWidth - optimumIconCellWidth);
        }
    }

    /**
     * Sets the overflow behavious: if true legends flow to next pages.
     * 
     * @param overflow
     */
    public void setOverflow(boolean overflow) {
        this.overflow = overflow;
    }

    /**
     * Decides if the renderer fail if broken image is received (defaults to true).
     * 
     * @param failOnBrokenUrl
     */
    public void setFailOnBrokenUrl(boolean failOnBrokenUrl) {
        this.failOnBrokenUrl = failOnBrokenUrl;
    }

    /**
     * set maximum number of columns in the legend table
     * 
     * @param maxColumns
     */
    public void setMaxColumns(int maxColumns) {
        if (maxColumns <= 0) {
            throw new InvalidValueException("maxColumns", maxColumns);
        }
        this.maxColumns = maxColumns;
    }

    /**
     * set maximum width of legend items i.e. the legend tables
     *
     * @param maxWidth
     */
    public void setMaxWidth(double maxWidth) {
        this.maxWidth = getMaxValueIfZero((float) maxWidth, "maxWidth");
    }

    /**
     * set maximum height of a legend column
     *
     * @param maxHeight if 0 means the column can be as hight as possible
     */
    public void setMaxHeight(double maxHeight) {
        this.maxHeight = getMaxValueIfZero((float) maxHeight, "maxHeight");
    }

    /**
     * Sets a width to be fitted by the legend. If not 0 (the default) the legend table is resized to be contained on the given width.
     */
    public void setFitWidth(float fitWidth) {
        this.fitWidth = fitWidth;
    }

    /**
     * Sets an height to be fitted by the legend. If not 0 (the default) the legend table is resized to be contained on the given height.
     */
    public void setFitHeight(float fitHeight) {
        this.fitHeight = fitHeight;
    }

    /**
     * 1.0 or null for no scaling &gt;1.0 to increase size, &lt; 1.0 to decrease
     * 
     * @param scale scale icon/image by this
     */
    public void setDefaultScale(double scale) {
        this.scale = (float) scale;
        if (scale < 0.0) {
            throw new InvalidValueException("scale", scale);
        }
        if (scale == 0f) {
            this.scale = 1f;
        }
    }

    /**
     * Whether legend icons/images should appear on the same line as the legend text, has nothing to do with multi-column layout.
     *
     * @param inline true of false
     */
    public void setInline(boolean inline) {
        this.inline = inline;
    }

    /**
     * maximum width a legend icon/image can have currently SVG icons are scaled to fit this
     *
     * @param maxIconWidth
     */
    public void setIconMaxWidth(double maxIconWidth) {
        this.iconMaxWidth = (float) maxIconWidth;
        if (maxIconWidth < 0.0) {
            throw new InvalidValueException("maxIconWidth", maxIconWidth);
        }
        if (maxIconWidth == 0f) {
            this.iconMaxWidth = Float.MAX_VALUE;
        }
    }

    /**
     * maximum height of legend icon/image currently SVG icons get scaled to this if not present icons get scaled preserving ratio with iconMaxWidth
     *
     * @param maxIconHeight
     */
    public void setIconMaxHeight(double maxIconHeight) {
        this.iconMaxHeight = getMaxValueIfZero((float) maxIconHeight, "maxIconHeight");
    }

    /**
     * horizontal indentation of class legend items
     *
     * @param classIndentation
     */
    public void setClassIndentation(double classIndentation) {
        this.classIndentation = (float) classIndentation;
        if (classIndentation < 0.0) {
            throw new InvalidValueException("classIndentation", classIndentation);
        }
    }

    /**
     * font of class legend items' texts
     *
     * @param classFont
     */
    public void setClassFont(String classFont) {
        this.classFont = classFont;
    }

    /**
     * font size for class legend items' texts
     *
     * @param classFontSize
     */
    public void setClassFontSize(double classFontSize) {
        this.classFontSize = (float) classFontSize;
        if (classFontSize < 0.0) {
            throw new InvalidValueException("classFontSize", classFontSize);
        }
    }

    public String getClassFont() {
        return classFont;
    }

    /**
     * layers' texts font
     *
     * @return Font used for layers' texts but not for classes
     */
    protected Font getLayerPdfFont() {
        return FontFactory.getFont(layerFont, fontEncoding, (float) layerFontSize);
    }

    /**
     * classes' texts font
     *
     * @return Font used for class items
     */
    protected Font getClassPdfFont() {
        return FontFactory.getFont(classFont, fontEncoding, (float) classFontSize);
    }

    /**
     * vertical space AFTER the legend items
     *
     * @param layerSpace
     */
    public void setLayerSpace(double layerSpace) {
        this.layerSpace = (float) layerSpace;
        if (layerSpace < 0.0) {
            throw new InvalidValueException("layerSpace", layerSpace);
        }
    }

    /**
     * vertical space AFTER class legend items
     *
     * @param classSpace
     */
    public void setClassSpace(double classSpace) {
        this.classSpace = (float) classSpace;
        if (classSpace < 0.0) {
            throw new InvalidValueException("classSpace", classSpace);
        }
    }

    /**
     * @param layerFont Font name used for layer items, not classes
     */
    public void setLayerFont(String layerFont) {
        this.layerFont = layerFont;
    }

    /**
     * @param layerFontSize font size used for layer items
     */
    public void setLayerFontSize(double layerFontSize) {
        this.layerFontSize = (float) layerFontSize;
        if (layerFontSize < 0.0) {
            throw new InvalidValueException("layerFontSize", layerFontSize);
        }
    }

    /**
     * Font encoding
     *
     * @param fontEncoding
     */
    public void setFontEncoding(String fontEncoding) {
        this.fontEncoding = fontEncoding;
    }

    /**
     * CSS style margin of each legend column
     *
     * @param columnMargin
     */
    public void setColumnMargin(String columnMargin) {
        this.columnPadding = getFloatCssValues(columnMargin);
    }

    /**
     * set the horizontal alignment of legend items inside the table and the table itself
     *
     * @param value left|center|right
     */
    public void setHorizontalAlignment(String value) {
        if (value.equalsIgnoreCase("left")) {
            this.horizontalAlignment = Element.ALIGN_LEFT;
        } else if (value.equalsIgnoreCase("right")) {
            this.horizontalAlignment = Element.ALIGN_RIGHT;
        }
    }

    /**
     * CSS style padding around legend icon/image
     *
     * @param values
     */
    public void setIconPadding(String values) {
        this.iconPadding = getFloatCssValues(values);
    }

    /**
     * CSS style padding around legend text/name
     *
     * @param values
     */
    public void setTextPadding(String values) {
        this.textPadding = getFloatCssValues(values);
    }

    /**
     * get CSS like values for padding
     *
     * @param values space separated floating point values
     * @return css padding like array of floats
     */
    private float[] getFloatCssValues(String values) {
        float result[] = { 0f, 0f, 0f, 0f };
        String topRightBottomLeft[] = values.split(" ");
        int len = topRightBottomLeft.length > 4 ? 4 : topRightBottomLeft.length;
        switch (len) {
        default:
        case 1:
            for (int i = 0; i < 4; ++i) {
                result[i] = Float.parseFloat(topRightBottomLeft[0]);
            }
            break;
        case 2:
            result[0] = result[2] = Float.parseFloat(topRightBottomLeft[0]);
            result[1] = result[3] = Float.parseFloat(topRightBottomLeft[1]);
            break;
        case 3:
            result[0] = Float.parseFloat(topRightBottomLeft[0]);
            result[1] = result[3] = Float.parseFloat(topRightBottomLeft[1]);
            result[2] = Float.parseFloat(topRightBottomLeft[3]);
            break;
        case 4:
            for (int i = 0; i < len; ++i) {
                float val = Float.parseFloat(topRightBottomLeft[i]);
                result[i] = val;
            }
            break;
        }
        return result;
    }

    /**
     * Do we have borders for debugging?
     *
     * @param value
     */
    public void setBorders(boolean value) {
        this.borders = value;
    }

    /**
     * UNUSED (for now)
     *
     * @param textMaxHeight the textMaxHeight to set
     */
    /*
     * public void setTextMaxHeight(double textMaxHeight) { this.textMaxHeight = getMaxValueIfZero((float) textMaxHeight, "textMaxHeight"); }
     */
    /**
     * @param textMaxWidth the textMaxWidth to set
     */
    public void setTextMaxWidth(double textMaxWidth) {
        this.textMaxWidth = getMaxValueIfZero((float) textMaxWidth, "textMaxWidth");
    }

    /**
     * @param layerSpaceBefore the layerSpaceBefore to set
     */
    public void setLayerSpaceBefore(double layerSpaceBefore) {
        if (layerSpaceBefore < 0.0f) {
            throw new InvalidValueException("layerSpaceBefore", layerSpaceBefore);
        }
        this.layerSpaceBefore = (float) layerSpaceBefore;
    }

    /**
     * Set the flag indicates if it' need to render legend and names as one table to forbid the break between different columns
     * 
     * @param dontBreakItems
     */
    public void setDontBreakItems(boolean dontBreakItems) {
        this.dontBreakItems = dontBreakItems;
    }

    /**
     * Set the flag indicates if it' need to reorder legend in the columns to optimize it
     * 
     * @param reorderColumns
     */
    public void setReorderColumns(boolean reorderColumns) {
        this.reorderColumns = reorderColumns;
    }
}
