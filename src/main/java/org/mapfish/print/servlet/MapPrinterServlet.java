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

package org.mapfish.print.servlet;

import com.google.common.io.CharStreams;
import com.google.common.io.Closer;
import org.apache.log4j.Logger;
import org.pvalsecc.misc.FileUtilities;
import org.mapfish.print.utils.PJsonObject;
import org.mapfish.print.output.OutputFormat;
import org.mapfish.print.MapPrinter;
import org.mapfish.print.Constants;
import org.json.JSONWriter;

import org.json.JSONException;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.itextpdf.text.DocumentException;

/**
 * Main print servlet.
 */
public class MapPrinterServlet extends BaseMapServlet {
    public static final Logger SPEC_LOGGER = Logger.getLogger(BaseMapServlet.class.getPackage().toString() + ".spec");
    private static final long serialVersionUID = -4706371598927161642L;
    private static final String CONTEXT_TEMPDIR = "javax.servlet.context.tempdir";

    private static final String INFO_URL = "/info.json";
    private static final String PRINT_URL = "/print.pdf";
    private static final String CREATE_URL = "/create.json";
    protected static final String TEMP_FILE_PREFIX = "mapfish-print";
    private static final String TEMP_FILE_SUFFIX = ".printout";

    private String app = null;

    private static final int TEMP_FILE_PURGE_SECONDS = 10 * 60;

    private File tempDir = null;
    private String encoding = null;
    /**
     * Tells if a thread is alread purging the old temporary files or not.
     */
    private AtomicBoolean purging = new AtomicBoolean(false);
    /**
     * Map of temporary files.
     */
    private final Map<String, TempFile> tempFiles = new HashMap<String, TempFile>();

    protected void doGet(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse) throws ServletException, IOException {
        //do the routing in function of the actual URL
        String additionalPath = httpServletRequest.getPathInfo().trim();
        if (additionalPath.isEmpty()) {
            // handle an odd case where path info returns an empty string
            additionalPath = httpServletRequest.getServletPath();
        }
        if (additionalPath.equals(PRINT_URL)) {
            createAndGetPDF(httpServletRequest, httpServletResponse);
        } else if (additionalPath.equals(INFO_URL)) {
            getInfo(httpServletRequest, httpServletResponse, getBaseUrl(httpServletRequest));
        } else if (additionalPath.startsWith("/") && additionalPath.endsWith(TEMP_FILE_SUFFIX)) {
            getFile(httpServletRequest, httpServletResponse, additionalPath.substring(1, additionalPath.length() - TEMP_FILE_SUFFIX.length()));
        } else {
            error(httpServletResponse, "Unknown method: " + additionalPath, 404);
        }
    }

    protected void doPost(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse) throws ServletException, IOException {
        final String additionalPath = httpServletRequest.getPathInfo();
        if (additionalPath.equals(PRINT_URL)) {
            createAndGetPDF(httpServletRequest, httpServletResponse);
        } else if (additionalPath.equals(CREATE_URL)) {
            createPDF(httpServletRequest, httpServletResponse, getBaseUrl(httpServletRequest));
        } else {
            error(httpServletResponse, "Unknown method: " + additionalPath, 404);
        }
    }

    public void init() throws ServletException {
        //get rid of the temporary files that were present before the servlet was started.
        File dir = getTempDir();
        File[] files = dir.listFiles();
        for (File file : files) {
            deleteFile(file);
        }
    }

    public void destroy() {
        synchronized (tempFiles) {
            for (File file : tempFiles.values()) {
                deleteFile(file);
            }
            tempFiles.clear();
        }
        super.destroy();
    }

    /**
     * All in one method: create and returns the PDF to the client. Avoid to use
     * it, the accents in the spec are not all supported.
     */
    protected void createAndGetPDF(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse) {
        //get the spec from the query
        TempFile tempFile = null;
        String spec = null;
        try {
            httpServletRequest.setCharacterEncoding("UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
        if (httpServletRequest.getMethod() == "POST") {
            try {
                spec = getSpecFromPostBody(httpServletRequest);
            } catch (IOException e) {
                error(httpServletResponse, "Missing 'spec' in request body", 500);
                return;
            }
        } else {
            spec = httpServletRequest.getParameter("spec");
        }
        if (spec == null) {
            error(httpServletResponse, "Missing 'spec' parameter", 500);
            return;
        }

        try {
            tempFile = doCreatePDFFile(spec, httpServletRequest);
            sendPdfFile(httpServletResponse, tempFile, Boolean.parseBoolean(httpServletRequest.getParameter("inline")));
        } catch (Throwable e) {
            error(httpServletResponse, e);
        } finally {
            deleteFile(tempFile);
        }
    }

    /**
     * Create the PDF and returns to the client (in JSON) the URL to get the PDF.
     */
    protected void createPDF(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse, String basePath) throws ServletException {
        TempFile tempFile = null;
        try {
            purgeOldTemporaryFiles();

            String spec = getSpecFromPostBody(httpServletRequest);
            tempFile = doCreatePDFFile(spec, httpServletRequest);
            if (tempFile == null) {
                error(httpServletResponse, "Missing 'spec' parameter", 500);
                return;
            }
        } catch (Throwable e) {
            deleteFile(tempFile);
            error(httpServletResponse, e);
            return;
        }

        final String id = generateId(tempFile);
        httpServletResponse.setContentType("application/json; charset=utf-8");
        PrintWriter writer = null;
        try {
            writer = httpServletResponse.getWriter();
            JSONWriter json = new JSONWriter(writer);
            json.object();
            {
                json.key("getURL").value(basePath + "/" + id + TEMP_FILE_SUFFIX);
            }
            json.endObject();
        } catch (JSONException e) {
            deleteFile(tempFile);
            throw new ServletException(e);
        } catch (IOException e) {
            deleteFile(tempFile);
            throw new ServletException(e);
        } finally {
            if(writer != null) {
                writer.close();
            }
        }
        addTempFile(tempFile, id);
    }

    protected void addTempFile(TempFile tempFile, String id) {
        synchronized (tempFiles) {
            tempFiles.put(id, tempFile);
        }
    }

    protected String getSpecFromPostBody(HttpServletRequest httpServletRequest) throws IOException {
        if(httpServletRequest.getParameter("spec") != null) {
            return httpServletRequest.getParameter("spec");
        }
        BufferedReader data = new BufferedReader(new InputStreamReader(httpServletRequest.getInputStream(), StandardCharsets.UTF_8));

        Closer closer = Closer.create();
        try {
            final InputStreamReader reader = closer.register(new InputStreamReader(httpServletRequest.getInputStream(), getEncoding()));
            BufferedReader bufferedReader = closer.register(new BufferedReader(reader));
            final String spec = CharStreams.toString(bufferedReader);
            return spec;
        } finally {
            closer.close();
        }
    }
    
    /**
     * Get and cache the used Encoding.
     */
    protected String getEncoding() {
        if (encoding == null) {
        	encoding = getInitParameter("encoding");
        	LOGGER.debug("Using '" + encoding + "' to encode Inputcontent.");
        }
        if (encoding == null) {
            return "UTF-8";
        } else {
            return encoding;
        }
    }

    /**
     * To get the PDF created previously.
     */
    protected void getFile(HttpServletRequest req, HttpServletResponse httpServletResponse, String id) throws IOException, ServletException {
        final TempFile file;
        synchronized (tempFiles) {
            file = tempFiles.get(id);
        }
        if (file == null) {
            error(httpServletResponse, "File with id=" + id + " unknown", 404);
            return;
        }
        sendPdfFile(httpServletResponse, file, Boolean.parseBoolean(req.getParameter("inline")));
    }

    /**
     * To get (in JSON) the information about the available formats and CO.
     */
    protected void getInfo(HttpServletRequest req, HttpServletResponse resp, String basePath) throws ServletException, IOException {
        app = req.getParameter("app");
        //System.out.println("app = "+app);

        MapPrinter printer = getMapPrinter(app);
        try {
            resp.setContentType("application/json; charset=utf-8");
            final PrintWriter writer = resp.getWriter();

            try {
                final String var = req.getParameter("var");
                if (var != null) {
                    writer.print(var + "=");
                }

                JSONWriter json = new JSONWriter(writer);
                try {
                    json.object();
                    {
                        printer.printClientConfig(json);
                        String urlToUseInSpec = basePath;

                        String proxyUrl = printer.getConfig().getProxyBaseUrl();
                        if (proxyUrl != null) {
                            urlToUseInSpec = proxyUrl;
                        }
                        json.key("printURL").value(urlToUseInSpec + PRINT_URL);
                        json.key("createURL").value(urlToUseInSpec + CREATE_URL);
                        if (app != null) {
                            json.key("app").value(app);
                        }
                    }
                    json.endObject();
                } catch (JSONException e) {
                    throw new ServletException(e);
                }
                if (var != null) {
                    writer.print(";");
                }
            } finally {
                writer.close();
            }
        } finally {
            if (printer != null) {
                printer.stop();
            }
        }
    }


    /**
     * Do the actual work of creating the PDF temporary file.
     * @throws InterruptedException
     */
    protected TempFile doCreatePDFFile(String spec, HttpServletRequest httpServletRequest) throws IOException, DocumentException, ServletException, InterruptedException {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Generating PDF for spec=" + spec);
        }

        if (SPEC_LOGGER.isInfoEnabled()) {
            SPEC_LOGGER.info(spec);
        }

        PJsonObject specJson = MapPrinter.parseSpec(spec);
        if (specJson.has("app")) {
            app = specJson.getString("app");
        } else {
            app = null;
        }

        MapPrinter mapPrinter = getMapPrinter(app);
        try {
            Map<String, String> headers = new HashMap<String, String>();
            TreeSet<String> configHeaders = mapPrinter.getConfig().getHeaders();
            if (configHeaders == null) {
                configHeaders = new TreeSet<String>();
                configHeaders.add("Referer");
                configHeaders.add("Cookie");
            }
            for (Iterator<String> header_iter = configHeaders.iterator(); header_iter.hasNext();) {
                String header = header_iter.next();
                if (httpServletRequest.getHeader(header) != null) {
                    headers.put(header, httpServletRequest.getHeader(header));
                }
            }

            final OutputFormat outputFormat = mapPrinter.getOutputFormat(specJson);
            // create a temporary file that will contain the PDF
            final File tempJavaFile = File.createTempFile(TEMP_FILE_PREFIX,
                    "." + outputFormat.getFileSuffix() + TEMP_FILE_SUFFIX, getTempDir());
            TempFile tempFile = new TempFile(tempJavaFile, specJson, outputFormat);

            FileOutputStream out = null;
            try {
                out = new FileOutputStream(tempFile);
                if(mapPrinter.getConfig().isAddForwardedFor()) {
                    String ipAddress = httpServletRequest.getHeader("X-FORWARDED-FOR");  
                    if (ipAddress != null) {
                        String[] ips = ipAddress.split(", ");
                        ipAddress = ips[0];
                    } else {
                    ipAddress = httpServletRequest.getRemoteAddr();  
                    }
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("Forwarded for: " + ipAddress);
                    }
                    headers.put("X-Forwarded-For", ipAddress);
                }
                mapPrinter.print(specJson, out, headers);

                return tempFile;
            } catch (IOException e) {
                deleteFile(tempFile);
                throw e;
            } catch (DocumentException e) {
                deleteFile(tempFile);
                throw e;
            } catch (InterruptedException e) {
                deleteFile(tempFile);
                throw e;
            } finally {
                if (out != null) {
                    out.close();
                }
            }
        } finally {
            if (mapPrinter != null) {
                mapPrinter.stop();
            }
        }
    }

    /**
     * copy the PDF into the output stream
     */
    protected void sendPdfFile(HttpServletResponse httpServletResponse, TempFile tempFile, boolean inline) throws IOException, ServletException {
        FileInputStream pdf = new FileInputStream(tempFile);
        final OutputStream response = httpServletResponse.getOutputStream();
        MapPrinter mapPrinter = getMapPrinter(app);
        try {
            httpServletResponse.setContentType(tempFile.contentType());
            if (!inline) {
                final String fileName = tempFile.getOutputFileName(mapPrinter);
                httpServletResponse.setHeader("Content-disposition", "attachment; filename=" + fileName);
            }
            FileUtilities.copyStream(pdf, response);
        } finally {
            if (mapPrinter != null) {
                mapPrinter.stop();
            }
            try {
                pdf.close();
            } finally {
                response.close();
            }
        }
    }

    /**
     * Send an error XXX to the client with an exception
     */
    protected void error(HttpServletResponse httpServletResponse, Throwable e) {
        PrintWriter out = null;
        try {
            httpServletResponse.setContentType("text/plain");
            httpServletResponse.setStatus(500);
            out = httpServletResponse.getWriter();
            out.println("Error while generating PDF:");
            e.printStackTrace(out);

            LOGGER.error("Error while generating PDF", e);
        } catch (IOException ex) {
            throw new RuntimeException(e);
        } finally {
            if (out != null) {
                out.close();
            }
        }
    }

    /**
     * Send an error XXX to the client with a message
     */
    protected void error(HttpServletResponse httpServletResponse, String message, int code) {
        PrintWriter out = null;
        try {
            httpServletResponse.setContentType("text/plain");
            httpServletResponse.setStatus(code);
            out = httpServletResponse.getWriter();
            out.println("Error while generating PDF:");
            out.println(message);

            LOGGER.error("Error while generating PDF: " + message);
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        } finally {
            if (out != null) {
                out.close();
            }
        }
    }

    /**
     * Get and cache the temporary directory to use for saving the generated PDF files.
     */
    protected File getTempDir() {
        if (tempDir == null) {
            String tempDirPath = getInitParameter("tempdir");
            if (tempDirPath == null) {
            	tempDirPath = System.getProperty("MAPFISH_PDF_FOLDER");
            }
            if (tempDirPath != null && !"".equals(tempDirPath.trim())) {
                tempDir = new File(tempDirPath);
            } else {
                tempDir = (File) getServletContext().getAttribute(CONTEXT_TEMPDIR);
            }
            if (!tempDir.exists() && !tempDir.mkdirs()) {
                throw new RuntimeException("unable to create dir:" + tempDir);
            }

        }
        LOGGER.debug("Using '" + tempDir.getAbsolutePath() + "' as temporary directory");
        return tempDir;
    }

    /**
     * If the file is defined, delete it.
     */
    protected void deleteFile(File file) {
        if (file != null) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Deleting PDF file: " + file.getName());
            }
            if (!file.delete()) {
                LOGGER.warn("Cannot delete file:" + file.getAbsolutePath());
            }
        }
    }

    /**
     * Get the ID to use in function of the filename (filename without the prefix and the extension).
     */
    protected String generateId(File tempFile) {
        final String name = tempFile.getName();
        return name.substring(
                TEMP_FILE_PREFIX.length(),
                name.length() - TEMP_FILE_SUFFIX.length());
    }

    protected String getBaseUrl(HttpServletRequest httpServletRequest) {
        final String additionalPath = httpServletRequest.getPathInfo();
        String fullUrl = httpServletRequest.getParameter("url");
        if (fullUrl != null) {
            return fullUrl.replaceFirst(additionalPath + "$", "");
        } else {
			String customUrl = System.getProperty("PRINT_BASE_URL");
			if(customUrl != null && !"".equals(customUrl.trim())) {
				return customUrl.replaceFirst(additionalPath + "$", "");
			} else {
			    return httpServletRequest.getRequestURL().toString().replaceFirst(additionalPath + "$", "");
			}
		}
    }

    /**
     * Will purge all the known temporary files older than TEMP_FILE_PURGE_SECONDS.
     */
    protected void purgeOldTemporaryFiles() {
        if (!purging.getAndSet(true)) {
            final long minTime = System.currentTimeMillis() - TEMP_FILE_PURGE_SECONDS * 1000L;
            synchronized (tempFiles) {
                Iterator<Map.Entry<String, TempFile>> it = tempFiles.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<String, TempFile> entry = it.next();
                    if (entry.getValue().creationTime < minTime) {
                        deleteFile(entry.getValue());
                        it.remove();
                    }
                }
            }
            purging.set(false);
        }
    }

    static class TempFile extends File {
        private static final long serialVersionUID = 455104129549002361L;
        private final long creationTime;
        public final String printedLayoutName;
        public final String outputFileName;
        private final String contentType;
        private String suffix;

        public TempFile(File tempFile, PJsonObject jsonSpec, OutputFormat format) {
            super(tempFile.getAbsolutePath());
            creationTime = System.currentTimeMillis();
            this.outputFileName = jsonSpec.optString(Constants.OUTPUT_FILENAME_KEY);
            this.printedLayoutName = jsonSpec.optString(Constants.JSON_LAYOUT_KEY, null);

            this.suffix = format.getFileSuffix();
            this.contentType = format.getContentType();
        }

        public String getOutputFileName(MapPrinter mapPrinter) {
            if(outputFileName != null) {
                return formatFileName(suffix, outputFileName, new Date());
            } else {
                return formatFileName(suffix, mapPrinter.getOutputFilename(printedLayoutName, getName()), new Date());
            }
        }


        public static String formatFileName(String suffix, String startingName, Date date) {
            Matcher matcher = Pattern.compile("\\$\\{(.+?)\\}").matcher(startingName);
            HashMap<String,String> replacements = new HashMap<String,String>();
            while(matcher.find()) {
                String pattern = matcher.group(1);
                String key = "${"+pattern+"}";
                replacements.put(key, findReplacement(pattern, date));
            }
            String result = startingName;
            for(Map.Entry<String,String> entry: replacements.entrySet()) {
                result = result.replace(entry.getKey(), entry.getValue());
            }

            while(suffix.startsWith(".")) {
                suffix = suffix.substring(1);
            }
            if(suffix.isEmpty() || result.toLowerCase().endsWith("."+suffix.toLowerCase())) {
                return result;
            } else {
                return result+"."+suffix;
            }
        }

        public static String cleanUpName(String original) {
            return original.replace(",","").replaceAll("\\s+", "_");
        }

        private static String findReplacement(String pattern, Date date) {
            if (pattern.toLowerCase().equals("date")) {
                return cleanUpName(DateFormat.getDateInstance().format(date));
            } else if (pattern.toLowerCase().equals("datetime")) {
                return cleanUpName(DateFormat.getDateTimeInstance().format(date));
            } else if (pattern.toLowerCase().equals("time")) {
                return cleanUpName(DateFormat.getTimeInstance().format(date));
            } else {
                try {
                    return new SimpleDateFormat(pattern).format(date);
                } catch (Exception e) {
                    LOGGER.error("Unable to format timestamp according to pattern: "+pattern, e);
                    return "${"+pattern+"}";
                }
            }
        }

        public String contentType() {
            return contentType;
        }
    }
}
