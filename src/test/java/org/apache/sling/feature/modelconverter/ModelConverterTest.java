/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.sling.feature.modelconverter;

import org.apache.sling.feature.Bundles;
import org.apache.sling.feature.Configurations;
import org.apache.sling.feature.Extension;
import org.apache.sling.feature.ExtensionType;
import org.apache.sling.feature.Extensions;
import org.apache.sling.feature.builder.FeatureProvider;
import org.apache.sling.feature.io.file.ArtifactHandler;
import org.apache.sling.feature.io.file.ArtifactManager;
import org.apache.sling.feature.io.file.ArtifactManagerConfig;
import org.apache.sling.feature.io.json.FeatureJSONReader;
import org.apache.sling.provisioning.model.Artifact;
import org.apache.sling.provisioning.model.ArtifactGroup;
import org.apache.sling.provisioning.model.Configuration;
import org.apache.sling.provisioning.model.Feature;
import org.apache.sling.provisioning.model.KeyValueMap;
import org.apache.sling.provisioning.model.MergeUtility;
import org.apache.sling.provisioning.model.Model;
import org.apache.sling.provisioning.model.ModelConstants;
import org.apache.sling.provisioning.model.ModelUtility;
import org.apache.sling.provisioning.model.ModelUtility.ResolverOptions;
import org.apache.sling.provisioning.model.ModelUtility.VariableResolver;
import org.apache.sling.provisioning.model.RunMode;
import org.apache.sling.provisioning.model.Section;
import org.apache.sling.provisioning.model.io.ModelReader;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ModelConverterTest {

    private static Logger LOGGER = LoggerFactory.getLogger(ModelConverterTest.class);

    @Rule
    public TestName name = new TestName();

    private Path tempDir;
    private ArtifactManager artifactManager;
    private FeatureProvider featureProvider;

    // If there is a test output folder provided (test.prov.files.tempdir) then the tests can fail because of conflicts
    // and it is hard to figure out what the test did. So we place them in their own folder
    private static Random random = new Random(System.currentTimeMillis());

    @Before
    public void setup() throws Exception {
        String tmpDir = System.getProperty("test.prov.files.tempdir");
        Path parent;
        if (tmpDir != null) {
            parent = Paths.get(tmpDir);
            LOGGER.info("Using provided directory for generated files: '{}'", parent);
            // Create sub folder with test method name in it to separate the test output from each other
            File child = new File(parent.toFile(), "test-" + name.getMethodName() + "-" + random.nextInt(1000));
            if(!child.mkdir()) { fail("Could not create sub folder: " + child.getPath()); }
            tempDir = child.toPath();
        } else {
            tempDir = Files.createTempDirectory(getClass().getSimpleName() + "-" + name.getMethodName());
        }
        artifactManager = ArtifactManager.getArtifactManager(
                new ArtifactManagerConfig());
        featureProvider =
            id -> {
                try {
                    File file = artifactManager.getArtifactHandler(id.toMvnUrl()).getFile();
                    try (Reader reader = new FileReader(file)) {
                        return FeatureJSONReader.read(reader, file.toURI().toURL().toString());
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            };
    }

    @After
    public void tearDown() throws Exception {
        boolean doDelete = System.getProperty("test.prov.files.tempdir") == null;
        String noDelete = System.getProperty("test.prov.no.delete");
        doDelete = doDelete && !"true".equalsIgnoreCase(noDelete);
        if(doDelete) {
            // Delete the temp dir again
            Files.walk(tempDir)
                .sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(File::delete);
        }
    }

    @Test
    public void testBootToProvModel() throws Exception {
        testConvertToProvisioningModel("/boot.json", "/boot.txt");
    }

    @Test
    public void testBootToFeature() throws Exception {
        testConvertToFeature("/boot.txt", "/boot.json");
    }

    @Test
    public void testOakToProvModel() throws Exception {
        testConvertToProvisioningModel("/oak.json", "/oak.txt");
    }

    @Test
    public void testSeparateRunmodeFiles() throws Exception {
        testConvertToProvisioningModel(
            new String[] {
                    "/runmodeseparation/oak_no_runmode.json",
                    "/runmodeseparation/oak_mongo.json",
                    "/runmodeseparation/oak_tar.json"},
            "/oak.txt");
    }

    @Test
    public void testDifferentSourceIdenticalProvModel() throws Exception {
        String dir1 = System.getProperty("test.json.dir1");
        String dir2 = System.getProperty("test.json.dir2");

        File[] files1, files2;
        if (dir1 != null && dir2 != null) {
            files1 = new File(dir1).listFiles((d, n) -> n.endsWith(".json"));
            files2 = new File(dir2).listFiles((d, n) -> n.endsWith(".json"));
        } else {
            files1 = new File[] {
                  getFile("/runmodeseparation/oak_no_runmode.json"),
                  getFile("/runmodeseparation/oak_mongo.json"),
                  getFile("/runmodeseparation/oak_tar.json")
            };
            files2 = new File[] {getFile("/oak.json")};
        }

        testConvertToProvisioningModel(files2, files1);
    }

    private File getFile(String f) throws URISyntaxException {
        return new File(getClass().getResource(f).toURI());
    }

    @Test
    public void testOakToFeature() throws Exception {
        testConvertToFeature("/oak.txt", "/oak.json");
    }

    @Test
    public void testRepoinitToProvModel() throws Exception {
        testConvertToProvisioningModel("/repoinit.json", "/repoinit.txt");
    }

    @Test
    public void testRepoinitToFeature() throws Exception {
        testConvertToFeature("/repoinit.txt", "/repoinit.json");
    }

    @Test
    public void testLaunchpadToProvModel() throws Exception {
        testConvertToProvisioningModel("/launchpad.json", "/launchpad.txt");
    }

    @Test
    public void testLaunchpadToFeature() throws Exception {
        testConvertToFeature("/launchpad.txt", "/launchpad.json");
    }

    @Test
    public void testSimpleToProvModel() throws Exception {
        testConvertToProvisioningModel("/simple.json", "/simple.txt");
    }

    @Test
    public void testSimpleToFeature() throws Exception {
        testConvertToFeature("/simple.txt", "/simple.json");
    }

    @Test
    public void testSimpleInheritsToProvModel() throws Exception {
        testConvertToProvisioningModel("/simple_inherits.json", "/simple_inherits.txt", "/simple.json");
        testConvertToProvisioningModel("/simple_inherits.json", "/simple_inherits.txt", "/simple.json", "/simple_inherits.json");
    }

    @Test
    public void testSimpleInheritsViaMavenRepoToProvModel() throws Exception {
        artifactManager = Mockito.mock(ArtifactManager.class);
        Mockito.when(artifactManager.getArtifactHandler(Mockito.anyString())).then(new Answer<ArtifactHandler>() {
            @Override
            public ArtifactHandler answer(InvocationOnMock in) throws Throwable {
                String url = in.getArgument(0).toString();

                if (url.endsWith("simple_inherits.json")) {
                    return new ArtifactHandler(url, new File(url));
                } else if ("mvn:generated/simple/1.0.0".equals(url)) {
                    return new ArtifactHandler(url, new File(getClass().getResource("/simple.json").toURI()));
                }
                return null;
            }
        });

        testConvertToProvisioningModel("/simple_inherits.json", "/simple_inherits.txt");
    }

    @Test
    public void testSimple2ToProvModel() throws Exception {
        testConvertToProvisioningModel("/simple2.json", "/simple2.txt");
    }

    @Test
    public void testSimple2ToFeature() throws Exception {
        testConvertToFeature("/simple2.txt", "/simple2.json");
    }

    @Test
    public void testProvModelRoundtripFolder() throws Exception {
        String dir = System.getProperty("test.prov.files.dir");
        File filesDir;
        if (dir != null) {
            filesDir = new File(dir);
        } else {
            filesDir = new File(getClass().getResource("/repoinit.txt").toURI()).
                getParentFile();
        }

        for (File f : filesDir.listFiles((d, n) -> n.endsWith(".txt"))) {
            LOGGER.info(name.getMethodName() + ", test file: '{}'", f);
            testConvertFromProvModelRoundTrip(f);
        }
    }

    @Test
    public void testModelGAV() throws Exception {
        String originalProvModel = "/boot.txt";
        String expectedJSON = "/boot_gav.json";

        File inFile = new File(getClass().getResource(originalProvModel).toURI());

        Map<String, Object> options = new HashMap<>();
        options.put("groupId", "testing123");
        options.put("version", "4.5.6");
        List<File> files = ProvisioningToFeature.convert(inFile, tempDir.toFile(), options);
        assertEquals("The testing code expects a single output file here", 1, files.size());
        File outFile = files.get(0);

        File expectedFile = new File(getClass().getResource(expectedJSON).toURI());
        org.apache.sling.feature.Feature expected = FeatureToProvisioning.getFeature(expectedFile);
        org.apache.sling.feature.Feature actual = FeatureToProvisioning.getFeature(outFile);
        assertFeaturesEqual(expected, actual);
    }

    @Test
    public void testConvertToProvisioningModelOverwriteLogic() throws Exception {
        String originalJSON = "/boot.json";
        String expectedProvModel = "/boot.txt";

        File inFile = new File(getClass().getResource(originalJSON).toURI());
        File outFile = new File(tempDir.toFile(), expectedProvModel + ".generated");

        FeatureToProvisioning.convert(inFile, outFile, featureProvider);
        List<String> orgLines = Files.readAllLines(outFile.toPath());
        assertNotEquals("Test precondition", "modified!", orgLines.get(orgLines.size() - 1));

        // Append to the output file:
        Files.write(outFile.toPath(), "\nmodified!".getBytes(), StandardOpenOption.APPEND);

        // Convert again and see that the output file is not modified
        FeatureToProvisioning.convert(inFile, outFile, featureProvider);

        List<String> lines = Files.readAllLines(outFile.toPath());
        assertEquals("modified!", lines.get(lines.size() - 1));

        // Modify the modification time of the generated file to be older than the input file
        outFile.setLastModified(inFile.lastModified() - 100000);
        FeatureToProvisioning.convert(inFile, outFile, featureProvider);

        List<String> owLines = Files.readAllLines(outFile.toPath());
        assertEquals("The file should have been overwritten since the source has modified since it's edit timestamp",
                orgLines, owLines);
    }

    @Test
    public void testConvertToFeature() throws Exception {
        System.out.println("*** Convert to Feature: " + tempDir.toString());
        File inFile = new File(getClass().getResource("/boot.txt").toURI());

        // If this test is run with others it might conflict with existing files -> create sub folder
        List<File> files = ProvisioningToFeature.convert(inFile, tempDir.toFile(), Collections.emptyMap());
        assertEquals("The testing code expects a single output file here", 1, files.size());
        File outFile = files.get(0);

        List<String> orgLines = Files.readAllLines(outFile.toPath());
        assertNotEquals("Test precondition", "modified!", orgLines.get(orgLines.size() - 1));

        // Append to the output file:
        Files.write(outFile.toPath(), "\nmodified!".getBytes(), StandardOpenOption.APPEND);

        System.out.println("*** Convert Again ***");
        // Convert again and see that the output file is not modified
        List<File> files2 = ProvisioningToFeature.convert(inFile, tempDir.toFile(), Collections.emptyMap());
        assertEquals("Should return the same file list", files, files2);

        System.out.println("*** Check if not overwritten ***");
        List<String> lines = Files.readAllLines(outFile.toPath());
        assertEquals("modified!", lines.get(lines.size() - 1));

        // Modify the modification time of the generated file to be older than the input file
        outFile.setLastModified(inFile.lastModified() - 100000);
        List<File> files3 = ProvisioningToFeature.convert(inFile, tempDir.toFile(), Collections.emptyMap());
        assertEquals("Should return the same file list", files, files3);

        List<String> owLines = Files.readAllLines(outFile.toPath());
        assertEquals("The file should have been overwritten since the source has modified since it's edit timestamp",
                orgLines, owLines);
    }

    @Test
    public void testMultipleRepoinitSections() throws Exception {
        testConvertToFeature("/more/repoinit-multiple.txt", "/more/repoinit-multiple.json");
    }

    public void testConvertFromProvModelRoundTrip(File orgProvModel) throws Exception {
        LOGGER.info("Roundtrip converting of file: '{}'", orgProvModel.getName());
        List<File> allGenerateProvisioningModelFiles = new ArrayList<>();

        List<File> generated = ProvisioningToFeature.convert(orgProvModel, tempDir.toFile(), Collections.emptyMap());

        for (File f : generated) {
            String baseName = f.getName().substring(0, f.getName().length() - ".json".length());
            assertFalse("File name cannot contain a colon", baseName.contains(":"));
            File genFile = new File(tempDir.toFile(), baseName + ".txt");
            allGenerateProvisioningModelFiles.add(genFile);
            FeatureToProvisioning.convert(f, genFile, featureProvider);
        }

        Model expected = readProvisioningModel(orgProvModel);
        Model actual = readProvisioningModel(allGenerateProvisioningModelFiles);
        assertModelsEqual(expected, actual);
    }

    public void testConvertToFeature(String originalProvModel, String expectedJSON) throws Exception {
        File inFile = new File(getClass().getResource(originalProvModel).toURI());

        List<File> files = ProvisioningToFeature.convert(inFile, tempDir.toFile(), Collections.emptyMap());
        assertEquals("The testing code expects a single output file here", 1, files.size());
        File outFile = files.get(0);

        File expectedFile = new File(getClass().getResource(expectedJSON).toURI());
        org.apache.sling.feature.Feature expected = FeatureToProvisioning.getFeature(expectedFile);
        org.apache.sling.feature.Feature actual = FeatureToProvisioning.getFeature(outFile);
        assertFeaturesEqual(expected, actual);
    }

    public void testConvertToProvisioningModel(String originalJSON, String expectedProvModel, String ... additionalContextFiles) throws URISyntaxException, IOException {
        File inFile = new File(getClass().getResource(originalJSON).toURI());
        File outFile = new File(tempDir.toFile(), expectedProvModel + ".generated");
        List<File> addFiles = new ArrayList<>();
        for (String af : additionalContextFiles) {
            addFiles.add(new File(getClass().getResource(af).toURI()));
        }

        FeatureToProvisioning.convert(inFile, outFile, featureProvider, addFiles.toArray(new File[] {}));

        File expectedFile = new File(getClass().getResource(expectedProvModel).toURI());
        Model expected = readProvisioningModel(expectedFile);
        Model actual = readProvisioningModel(outFile);
        assertModelsEqual(expected, actual);
    }

    public void testConvertToProvisioningModel(String [] jsonFiles, String expectedProvModel) throws URISyntaxException, IOException {
        List<File> inFiles = new ArrayList<>();
        for (String jf : jsonFiles) {
            inFiles.add(new File(getClass().getResource(jf).toURI()));
        }

        List<File> generatedFiles = convertFeatureFilesToProvisioningModel(inFiles.toArray(new File[] {}));

        File expectedFile = new File(getClass().getResource(expectedProvModel).toURI());
        Model expected = readProvisioningModel(expectedFile);
        Model actual = readProvisioningModel(generatedFiles);
        assertModelsEqual(expected, actual);
        assertModelsEqual(actual, expected);
    }

    public void testConvertToProvisioningModel(File[] jsonFiles1, File[] jsonFiles2) throws URISyntaxException, IOException {
        List<File> generatedFiles1 = convertFeatureFilesToProvisioningModel(jsonFiles1);
        List<File> generatedFiles2 = convertFeatureFilesToProvisioningModel(jsonFiles2);

        LOGGER.debug("Generated Files 1: '{}'", generatedFiles1);
        LOGGER.debug("Generated Files 2: '{}'", generatedFiles2);
        Model actual1 = readProvisioningModel(generatedFiles1);
        Model actual2 = readProvisioningModel(generatedFiles2);
        assertModelsEqual(actual1, actual2);
        assertModelsEqual(actual2, actual1);
    }

    private List<File> convertFeatureFilesToProvisioningModel(File[] jsonFiles) throws URISyntaxException, IOException {
        List<File> generatedFiles = new ArrayList<>();
        for (File inFile : jsonFiles) {
            File outFile;
            int counter = 0;
            do {
                outFile = new File(tempDir.toFile(), inFile.getName() + (counter++) + ".txt.generated");
            } while (outFile.exists());

            FeatureToProvisioning.convert(inFile, outFile, featureProvider);
            generatedFiles.add(outFile);
        }
        return generatedFiles;
    }

    private static Model readProvisioningModel(File modelFile) throws IOException {
        return readProvisioningModel(Collections.singletonList(modelFile));
    }

    private static Model readProvisioningModel(List<File> modelFiles) throws IOException {
        Model model = null;
        for (File modelFile : modelFiles) {
            try (FileReader fr = new FileReader(modelFile)) {
                Model nextModel = ModelReader.read(fr, modelFile.getAbsolutePath());

                if (model == null) {
                    model = nextModel;
                } else {
                    MergeUtility.merge(model, nextModel);
                }
            }
        }

        // Fix the configurations up from the internal format to the Dictionary-based format
        return ModelUtility.getEffectiveModel(model,
                new ResolverOptions().variableResolver(new VariableResolver() {
            @Override
            public String resolve(final Feature feature, final String name) {
                // Keep variables as-is in the model
                return "${" + name + "}";
            }
        }));
    }

    private void assertFeaturesEqual(org.apache.sling.feature.Feature expected, org.apache.sling.feature.Feature actual) {
        assertEquals(expected.getId(), actual.getId());
        assertEquals(expected.getTitle(), actual.getTitle());
        assertEquals(expected.getDescription(), actual.getDescription());
        assertEquals(expected.getVendor(), actual.getVendor());
        assertEquals(expected.getLicense(), actual.getLicense());

        assertEquals(expected.getVariables(), actual.getVariables());
        assertBundlesEqual(expected.getBundles(), actual.getBundles());
        assertConfigurationsEqual(expected.getConfigurations(), actual.getConfigurations(), expected.getBundles(), actual.getBundles());
        assertEquals(expected.getFrameworkProperties(), actual.getFrameworkProperties());
        assertExtensionEqual(expected.getExtensions(), actual.getExtensions());

        // Ignore caps and reqs, prototype and here since they cannot come from the prov model.
    }

    private void assertBundlesEqual(Bundles expected, Bundles actual) {
        for (Iterator<org.apache.sling.feature.Artifact> it = expected.iterator(); it.hasNext(); ) {
            org.apache.sling.feature.Artifact ex = it.next();

            boolean found = false;
            for (Iterator<org.apache.sling.feature.Artifact> it2 = actual.iterator(); it2.hasNext(); ) {
                org.apache.sling.feature.Artifact ac = it2.next();
                if (ac.getId().equals(ex.getId())) {
                    found = true;
                    assertEquals(ex.getMetadata(), ac.getMetadata());
                    break;
                }
            }
            assertTrue("Not found: " + ex, found);
        }
    }

    private void assertConfigurationsEqual(Configurations expected, Configurations actual, Bundles exBundles, Bundles acBundles) {
        for (Iterator<org.apache.sling.feature.Configuration> it = expected.iterator(); it.hasNext(); ) {
            org.apache.sling.feature.Configuration ex = it.next();

            boolean found = false;
            for (Iterator<org.apache.sling.feature.Configuration> it2 = actual.iterator(); it2.hasNext(); ) {
                org.apache.sling.feature.Configuration ac = it2.next();
                if (ex.getPid().equals(ac.getPid())) {
                    found = true;
                    assertConfigProps(ex, ac, exBundles, acBundles);
                }
            }
            assertTrue(found);
        }
    }

    private void assertConfigProps(org.apache.sling.feature.Configuration expected, org.apache.sling.feature.Configuration actual, Bundles exBundles, Bundles acBundles) {
        assertTrue("Configurations not equal: " + expected.getProperties() + " vs " + actual.getProperties(),
                configPropsEqual(expected.getProperties(), actual.getProperties()));
    }

    private boolean configPropsEqual(Dictionary<String, Object> d1, Dictionary<String, Object> d2) {
        if (d1.size() != d2.size()) {
            return false;
        }

        for (Enumeration<String> e = d1.keys(); e.hasMoreElements(); ) {
            String k = e.nextElement();
            Object v = d1.get(k);
            if (v instanceof Object[]) {
                if (!Arrays.equals((Object[]) v, (Object[]) d2.get(k)))
                    return false;
            } else {
                if (!v.equals(d2.get(k)))
                    return false;
            }
        }
        return true;
    }

    private void assertModelsEqual(Model expected, Model actual) {
        for (int i=0; i<expected.getFeatures().size(); i++) {
            Feature expectedFeature = expected.getFeatures().get(i);
            Feature actualFeature = actual.getFeatures().get(i);
            assertFeaturesEqual(expectedFeature, actualFeature);
        }
    }

    private void assertFeaturesEqual(Feature expected, Feature actual) {
        assertEquals(expected.getName(), actual.getName());
        assertEquals(expected.getVersion(), actual.getVersion());
        assertEquals(expected.getType(), actual.getType());
        assertRunModesEqual(expected.getName(), expected.getRunModes(), actual.getRunModes(), actual.getVariables());
        assertKVMapEquals(expected.getVariables(), actual.getVariables());
        assertSectionsEqual(expected.getAdditionalSections(), actual.getAdditionalSections());
    }

    private void assertRunModesEqual(String featureName, List<RunMode> expected, List<RunMode> actual, KeyValueMap<String> actualVariables) {
        assertEquals(expected.size(), actual.size());
        for (RunMode rm : expected) {
            boolean found = false;
            for (RunMode arm : actual) {
                if (runModesEqual(featureName, rm, arm, actualVariables)) {
                    found = true;
                    break;
                }

            }
            if (!found) {
                fail("Run Mode " + rm + " not found in actual list " + actual);
            }
        }
    }

    private boolean runModesEqual(String featureName, RunMode rm1, RunMode rm2, KeyValueMap<String> variables2) {
        if (rm1.getNames() == null) {
            if (rm2.getNames() != null)
                return false;
        } else {
            if (rm2.getNames() == null)
                return false;

            HashSet<String> names1 = new HashSet<>(Arrays.asList(rm1.getNames()));
            HashSet<String> names2 = new HashSet<>(Arrays.asList(rm2.getNames()));
            LOGGER.debug("Check Run Modes Name: first: '{}', second: '{}'", names1, names2);
            if (!names1.equals(names2))
                return false;
        }

        List<ArtifactGroup> ag1 = rm1.getArtifactGroups();
        List<ArtifactGroup> ag2 = rm2.getArtifactGroups();
        LOGGER.debug("Check Artifact Group Size: first: '{}', second: '{}'", ag1.size(), ag2.size());
        if (ag1.size() != ag2.size())
            return false;

        for (ArtifactGroup g1 : ag1) {
            boolean found = false;
            for (ArtifactGroup g2 : ag2) {
                if (artifactGroupsEquals(featureName, g1, g2, variables2)) {
                    found = true;
                    break;
                }
            }
            if (!found)
                return false;
        }

        List<Configuration> configs1 = new ArrayList<>();
        rm1.getConfigurations().iterator().forEachRemaining(configs1::add);
        List<Configuration> configs2 = new ArrayList<>();
        rm2.getConfigurations().iterator().forEachRemaining(configs2::add);
        LOGGER.debug("Check Artifact Configuration Size: first: '{}', second: '{}'", configs1.size(), configs2.size());
        if (configs1.size() != configs2.size())
            return false;

        for (int i=0; i < configs1.size(); i++) {
            Configuration cfg1 = configs1.get(i);

            boolean found = false;
            for (Configuration cfg2 : configs2) {
                if (cfg1.getFactoryPid() == null) {
                    if (cfg2.getFactoryPid() != null) {
                        continue;
                    }
                } else if (!cfg1.getFactoryPid().equals(cfg2.getFactoryPid())) {
                    continue;
                }

                if (!cfg1.getPid().equals(cfg2.getPid())) {
                    continue;
                }

                // Factory and ordinary PIDs are equal, so check the content
                found = true;

                if (!configPropsEqual(cfg1.getProperties(), cfg2.getProperties())) {
                    return false;
                }

                break;
            }
            if (!found) {
                // Configuration with this PID not found
                return false;
            }
        }

        Map<String, String> m1 = kvToMap(rm1.getSettings());
        Map<String, String> m2 = kvToMap(rm2.getSettings());

        LOGGER.debug("Check Artifact KV to Map: first: '{}', second: '{}'", m1, m2);
        return m1.equals(m2);
    }

    private Map<String, String> kvToMap(KeyValueMap<String> kvm) {
        Map<String, String> m = new HashMap<>();

        for (Map.Entry<String, String> entry : kvm) {
            m.put(entry.getKey(), entry.getValue());
        }

        return m;
    }

    private boolean artifactGroupsEquals(String featureName, ArtifactGroup g1, ArtifactGroup g2, KeyValueMap<String> variables2) {
        int sl1 = effectiveStartLevel(featureName, g1.getStartLevel());
        int sl2 = effectiveStartLevel(featureName, g2.getStartLevel());
        LOGGER.debug("Check Artifact Group Start Level, feature name: '{}', first: '{}', second: '{}'", featureName, sl1, sl2);
        if (sl1 != sl2)
            return false;

        List<Artifact> al1 = new ArrayList<>();
        g1.iterator().forEachRemaining(al1::add);

        List<Artifact> al2 = new ArrayList<>();
        g2.iterator().forEachRemaining(al2::add);

        for (int i=0; i<al1.size(); i++) {
            Artifact a1 = al1.get(i);
            String a1Version = a1.getVersion();
            String resolvedVersion = a1Version;
            Artifact a1Resolved = null;
            if(a1Version.startsWith("${") && a1Version.endsWith("}")) {
                String variableName = a1Version.substring(2, a1Version.length() - 1);
                String variableValue = variables2.get(variableName);
                LOGGER.debug("AG Variable Name: '{}', Variable Value: '{}'", variableName, variableValue);
                if(variableName != null) {
                    a1Resolved = new Artifact(a1.getGroupId(), a1.getArtifactId(), variableValue, a1.getClassifier(), a1.getType());
                }
            }
            boolean found = false;
            for (Iterator<Artifact> it = al2.iterator(); it.hasNext(); ) {
                Artifact a2 = it.next();
//                LOGGER.debug("Check Artifacts, first: '{}', second: '{}'", a1, a2);
                String a2Version = a2.getVersion();
                String resolvedVersion2 = a2Version;
                Artifact a2Resolved = null;
                if(a2Version.startsWith("${") && a2Version.endsWith("}")) {
                    String variableName = a2Version.substring(2, a2Version.length() - 1);
                    String variableValue = variables2.get(variableName);
                    LOGGER.debug("AG 2 Variable Name: '{}', Variable Value: '{}'", variableName, variableValue);
                    if(variableName != null) {
                        a2Resolved = new Artifact(a2.getGroupId(), a2.getArtifactId(), variableValue, a2.getClassifier(), a2.getType());
                    }
                }
                LOGGER.debug("Check Artifacts\nfirst  MVN URL: '{}'\nsecond MVN URL: '{}'", a1.toMvnUrl(), a2.toMvnUrl());
                if (a1.compareTo(a2) == 0) {
                    found = true;
                    it.remove();
                } else {
                    // If we have a resolved AG 1 then compare this one as well
                    if (a1Resolved != null) {
                        if (a1Resolved.compareTo(a2) == 0) {
                            LOGGER.debug("Resolved AG matches: '{}' -> remove", a1Resolved);
                            found = true;
                            it.remove();
                        }
                    } else if (a2Resolved != null) {
                        if (a1.compareTo(a2Resolved) == 0) {
                            LOGGER.debug("Resolved AG 2 matches: '{}' -> remove", a2Resolved);
                            found = true;
                            it.remove();
                        }
                    }
                }
            }
            if (!found) {
                return false;
            }
        }

        // Should have found all artifacts
        LOGGER.debug("Not found AGs: '{}'", al2);
        return (al2.size() == 0);
    }

    private int effectiveStartLevel(String featureName, int startLevel) {
        if (startLevel != 0)
            return startLevel;

        if (ModelConstants.FEATURE_BOOT.equals(featureName)) {
            return 1;
        } else {
            return 20;
        }
    }

    private void assertKVMapEquals(KeyValueMap<String> expected, KeyValueMap<String> actual) {
        assertEquals(kvToMap(expected), kvToMap(actual));
    }

    private void assertExtensionEqual(Extensions expected, Extensions actual) {
        assertEquals(expected.size(), actual.size());

        for (int i=0; i<expected.size(); i++) {
            // TODO support the fact that they may be out of order
            Extension ex = expected.get(i);
            Extension ac = actual.get(i);

            assertEquals(ex.getType(), ac.getType());
            assertEquals(ex.getName(), ac.getName());
            assertEquals(ex.isRequired(), ac.isRequired());

            if (ex.getType() == ExtensionType.TEXT) {
                String exTxt;
                String acTxt;
                if("repoinit".equals(ex.getName())) {
                    exTxt = ex.getText().replaceAll("\\s+", "").replaceAll("\"\",", "");
                    acTxt = ac.getText().replaceAll("\\s+", "").replaceAll("\"\",", "");
                } else {
                    exTxt = ex.getText().replaceAll("\\s+", "");
                    acTxt = ac.getText().replaceAll("\\s+", "");
                }
                assertEquals(exTxt, acTxt);
            } else if (ex.getType() == ExtensionType.JSON) {
                String exJson = ex.getJSON().replaceAll("\\s+", "").replaceAll("\"\",", "");
                String acJson = ac.getJSON().replaceAll("\\s+", "").replaceAll("\"\",", "");
                assertEquals(exJson, acJson);
            }

            /* TODO reinstantiate for Artifacts extentions
            assertEquals(ex.getArtifacts().size(), ac.getArtifacts().size());
            for (int j = 0; j<ex.getArtifacts().size(); j++) {
                org.apache.sling.feature.Artifact exa = ex.getArtifacts().get(j);
                org.apache.sling.feature.Artifact aca = ac.getArtifacts().get(j);
                assertEquals(exa.getId().toMvnId(), aca.getId().toMvnId());
            }
            */
        }
    }

    private void assertSectionsEqual(List<Section> expected, List<Section> actual) {
        assertEquals(expected.size(), actual.size());

        for (int i=0; i<expected.size(); i++) {
            Section esec = expected.get(i);
            Section asec = actual.get(i);
            assertEquals(esec.getName(), asec.getName());
            assertEquals(esec.getContents().replaceAll("\\s+", ""),
                    asec.getContents().replaceAll("\\s+", ""));
            assertEquals(esec.getAttributes(), asec.getAttributes());
        }
    }
}
