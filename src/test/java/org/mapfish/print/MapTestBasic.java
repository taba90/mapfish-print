package org.mapfish.print;


import com.codahale.metrics.MetricRegistry;


import com.itextpdf.text.Document;
import com.itextpdf.text.PageSize;
import com.itextpdf.text.pdf.PdfWriter;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.ConsoleAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TestName;
import org.mapfish.print.config.Config;
import org.mapfish.print.config.HostMatcher;
import org.mapfish.print.config.layout.Block;
import org.mapfish.print.config.layout.Layout;
import org.mapfish.print.config.layout.MainPage;
import org.mapfish.print.config.layout.MapBlock;
import org.mapfish.print.utils.PJsonObject;
import org.pvalsecc.misc.FileUtilities;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.*;

public abstract class MapTestBasic {

    protected final Logger logger = LogManager.getLogger(MapTestBasic.class);

    @Rule
    public TestName name = new TestName();

    protected RenderingContext context;

    private ThreadResources threadResources;

    @Before
    public void setUp() throws Exception {

        this.threadResources = new ThreadResources();
        this.threadResources.init();


        final LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        final Configuration lconfig = ctx.getConfiguration();
        PatternLayout.Builder builder=PatternLayout.newBuilder();
        builder.withPattern("%d{HH:mm:ss.SSS} [%t] %-5p %30.30c - %m%n")
                .withConfiguration(lconfig)
                .withCharset(Charset.forName("UTF-8"));
        final org.apache.logging.log4j.core.Layout layoutLog =builder.build();
        Appender appender = ConsoleAppender.createDefaultAppenderForLayout(layoutLog);
        appender.start();
        lconfig.addAppender(appender);
        ctx.updateLoggers();
        Configurator.setLevel(ctx.getRootLogger(), Level.DEBUG);


        Document doc = new Document(PageSize.A4);
        String baseDir = getBaseDir();
        OutputStream outFile = new FileOutputStream(baseDir + getClass().getSimpleName() + "_" + name.getMethodName() + ".pdf");
        PdfWriter writer = PdfWriter.getInstance(doc, outFile);
        writer.setFullCompression();
        Layout layout = new Layout();
        MainPage mainPage = new MainPage();
        final MapBlock mapBlock = new MapBlock();
        mainPage.setItems(new ArrayList<Block>(Arrays.asList(mapBlock)));
        layout.setMainPage(mainPage);
        Config config = new Config();
        try {
            config.setThreadResources(this.threadResources);
            config.setMetricRegistry(new MetricRegistry());
            config.setDpis(new TreeSet<Integer>(Arrays.asList(96, 190, 254)));
            config.setScales(new TreeSet<Number>(Arrays.asList(20000.0, 25000.0, 100000.0, 500000.0, 4000000.0)));
            List<HostMatcher> hosts = new ArrayList<HostMatcher>(1);
            hosts.add(HostMatcher.ACCEPT_ALL);
            config.setHosts(hosts);
            PJsonObject globalParams = createGlobalParams();
            context = new RenderingContext(doc, writer, config, globalParams, null, layout, Collections.<String, String>emptyMap());
        } finally {
            config.close();
        }
    }

	protected PJsonObject createGlobalParams() throws IOException {
		return new PJsonObject(new JSONObject(), "globalParams");
	}

    @After
    public void tearDown() throws Exception {
        this.threadResources.destroy();

        ((LoggerContext) LogManager.getContext(false)).reconfigure();

        context.getWriter().close();

        context = null;
    }


    private String getBaseDir() {
        //This test expects to be able to write files into the same directory the classes
        //are compiled to, in this case the build/classes/test directory
        String expectedPath = "build" + File.separator + "classes" + File.separator + "test";
        String baseDir = MapTestBasic.class.getClassLoader().getResource(".").getFile();
        if (baseDir.contains("pulse-java.jar")) {
            String[] paths = System.getProperty("java.class.path").split(File.pathSeparator);

            for (String path : paths) {
                if (path.contains(expectedPath) || path.contains("out/test/mapfish-print")) {
                    baseDir = path;
                }
            }
        }
        return baseDir;
    }


    protected PJsonObject loadJson(String fileName, Replacement... replacements) throws IOException {
        final String file = MapTestBasic.class.getClassLoader().getResource(fileName).getFile();
        String textFile = FileUtilities.readWholeTextFile(new File(file));
        for (Replacement replacement : replacements) {
            textFile = textFile.replace(replacement.tag, replacement.replacement);
        }
        return MapPrinter.parseSpec(textFile);
    }

    protected class Replacement {
        private final String tag;
        private final String replacement;

        public Replacement(String tag, Object replacement) {
            this.tag = tag;
            this.replacement = replacement.toString();
        }
    }

}
