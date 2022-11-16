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

package org.mapfish.print.map.renderers.vector;

import com.itextpdf.awt.geom.AffineTransform;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import org.mapfish.print.PDFUtils;
import org.mapfish.print.RenderingContext;
import org.mapfish.print.config.ColorWrapper;
import org.mapfish.print.utils.PJsonObject;

import com.itextpdf.text.FontFactory;
import com.itextpdf.text.pdf.BaseFont;
import com.itextpdf.text.pdf.PdfContentByte;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;

public class LabelRenderer {

    public static final Logger LOGGER = LogManager.getLogger(LabelRenderer.class);

    static void applyStyle(RenderingContext context, PdfContentByte dc,
            PJsonObject style, Geometry geometry, AffineTransform affineTransform) {
        /*
         * See Feature/Vector.js for more information about labels
         */
        String label = style.optString("label");

		if (label != null && label.length() > 0) {
			/*
			 * Valid values for horizontal alignment: "l"=left, "c"=center,
			 * "r"=right. Valid values for vertical alignment: "t"=top,
			 * "m"=middle, "b"=bottom.
			 */
			String labelAlign = style.optString("labelAlign", "cm");
			float labelXOffset = style.optFloat("labelXOffset", (float) 0.0);
			float labelYOffset = style.optFloat("labelYOffset", (float) 0.0);
			float labelRotation = style.optFloat("rotation", (float) 0.0);
			if (labelRotation == 0.0f) {
				labelRotation = style.optFloat("labelRotation", (float) 0.0);
			}
			String fontColor = style.optString("fontColor", "#000000");
			/* Supported itext fonts: COURIER, HELVETICA, TIMES_ROMAN */
			String fontFamily = style.optString("fontFamily", "HELVETICA");
			String font = style.optString("font");
            String fontEncoding = style.optString("fontEncoding");
            if (font != null && !FontFactory.isRegistered(font)) {
                LOGGER.info("Font: '" + font +
                		"' not registered, one of the supported fonts from 'fontFamily' will be used");            	
            }
            else if (!"COURIER".equalsIgnoreCase(fontFamily)
                    && !"HELVETICA".equalsIgnoreCase(fontFamily)
                    && !"TIMES_ROMAN".equalsIgnoreCase(fontFamily)) {

                LOGGER.info("Font family: '" + fontFamily +
                        "' not supported, supported ones are 'HELVETICA', " +
                        "'COURIER', 'TIMES_ROMAN', defaults to 'HELVETICA'");
                fontFamily = "HELVETICA";
            }
            
            String fontSize = style.optString("fontSize", "12");
            String fontWeight = style.optString("fontWeight", "normal");
            Coordinate center = geometry.getCentroid().getCoordinate();
            center = GeometriesRenderer.transformCoordinate(center, affineTransform);
			float f = context.getStyleFactor();
			BaseFont bf = PDFUtils
					.getBaseFont(font, fontEncoding, fontFamily, fontSize, fontWeight);
			float fontHeight = (float) Double.parseDouble(fontSize
					.toLowerCase().replaceAll("px", "")) * f;
			dc.setFontAndSize(bf, fontHeight);
			dc.setColorFill(ColorWrapper.convertColor(fontColor));
			String outlineColor = style.optString("labelOutlineColor", null);
			float outlineWidth = style.optFloat("labelOutlineWidth", 1);
			if (outlineColor != null) {
				dc.setTextRenderingMode(PdfContentByte.TEXT_RENDER_MODE_FILL_STROKE);
				dc.setColorStroke(ColorWrapper.convertColor(outlineColor));
				dc.setLineWidth(outlineWidth);
			}
			
			dc.beginText();
			dc.setTextMatrix((float) center.x + labelXOffset * f,
                (float) center.y + labelYOffset * f);
			float offset = PDFUtils
					.getVerticalOffset(labelAlign, fontHeight);
			float yOffset = (float)Math.cos(labelRotation * Math.PI / 180.0) * offset;
			float xOffset = (float)Math.sin(labelRotation * Math.PI / 180.0) * offset;
			dc.showTextAligned(
					PDFUtils.getHorizontalAlignment(labelAlign),
					label,
					(float) center.x + labelXOffset * f + xOffset,
					(float) center.y
							+ labelYOffset
							* f
							- yOffset,
				labelRotation);
			dc.endText();
		}
	}
}
