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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonReader;
import javax.json.JsonString;
import javax.json.JsonValue;

import org.apache.sling.feature.ArtifactId;
import org.apache.sling.feature.Extension;
import org.apache.sling.feature.ExtensionState;
import org.apache.sling.feature.ExtensionType;
import org.apache.sling.feature.builder.BuilderContext;
import org.apache.sling.feature.builder.FeatureBuilder;
import org.apache.sling.feature.builder.FeatureProvider;
import org.apache.sling.feature.io.json.FeatureJSONReader;
import org.apache.sling.provisioning.model.Artifact;
import org.apache.sling.provisioning.model.Configuration;
import org.apache.sling.provisioning.model.Feature;
import org.apache.sling.provisioning.model.Model;
import org.apache.sling.provisioning.model.RunMode;
import org.apache.sling.provisioning.model.Section;
import org.apache.sling.provisioning.model.io.ModelWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Converter that converts the feature model to the provisioning model.
 */
public class FeatureToProvisioning {
    private static final Logger LOGGER = LoggerFactory.getLogger(FeatureToProvisioning.class);
    static final String PROVISIONING_MODEL_NAME_VARIABLE = "provisioning.model.name";
    static final String PROVISIONING_RUNMODES = "provisioning.runmodes";

    public static void convert(File inputFile, File outputFile, FeatureProvider fp, File ... additionalInputFiles) throws UncheckedIOException {
        if (outputFile.exists()) {
            if (outputFile.lastModified() >= inputFile.lastModified()) {
                LOGGER.debug("Skipping the generation of {} as this file already exists and is not older.", outputFile);
                return;
            }
        }

        org.apache.sling.feature.Feature feature = getFeature(inputFile);
        if (feature.getPrototype() != null) {
            feature = handlePrototype(feature, additionalInputFiles, fp);
        }

        Object featureNameVar = feature.getVariables().remove(PROVISIONING_MODEL_NAME_VARIABLE);
        String provModelName;
        if (featureNameVar instanceof String) {
            provModelName = (String) featureNameVar;
        } else {
            provModelName = feature.getId().getArtifactId();
        }

        String runMode = feature.getVariables().remove(PROVISIONING_RUNMODES);
        String[] runModes = null;
        if (runMode != null) {
            runModes = runMode.split(",");
        }

        convert(provModelName, feature, inputFile.getName(), outputFile.getAbsolutePath(), runModes);
    }

    static org.apache.sling.feature.Feature getFeature(final File file) throws UncheckedIOException {
        try (final Reader r = new InputStreamReader(new FileInputStream(file), "UTF-8")) {
            return FeatureJSONReader.read(r, file.toURI().toURL().toString());
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }

    }

    private static org.apache.sling.feature.Feature handlePrototype(org.apache.sling.feature.Feature feature, File[] additionalFiles, FeatureProvider fp) throws UncheckedIOException {
        Map<ArtifactId, org.apache.sling.feature.Feature> features =
            Stream.of(additionalFiles)
                .map(FeatureToProvisioning::getFeature)
                .collect(Collectors.toMap(org.apache.sling.feature.Feature::getId, Function.identity()));

        BuilderContext bc = new BuilderContext(id -> features.containsKey(id) ? features.get(id) : fp.provide(id));

        return FeatureBuilder.assemble(feature, bc);
    }

    /**
     * Convert a feature to a provisioning model
     *
     * @param provModelName The name of the prov model
     * @param feature       The feature model to convert
     * @param outputFile    The output file to write the prov model to
     * @param runModes      The run modes of the feature model
     */
    private static void convert(String provModelName, org.apache.sling.feature.Feature feature, String featureFileName,
            String outputFile,
            String[] runModes) {
        Feature provModel = new Feature(provModelName);

        final Map<String, Feature> additionalFeatures = new HashMap<>();

        if (runModes != null && runModes.length == 0) {
            runModes = null;
        }
        org.apache.sling.provisioning.model.KeyValueMap<String> vars = provModel.getVariables();
        for (Map.Entry<String, String> entry : feature.getVariables().entrySet()) {
            vars.put(entry.getKey(), entry.getValue());
        }

        Map<org.apache.sling.feature.Configuration, org.apache.sling.feature.Artifact> configBundleMap = new HashMap<>();

        // bundles
        for (final org.apache.sling.feature.Artifact bundle : feature.getBundles()) {
            final ArtifactId id = bundle.getId();
            final Artifact newBundle = new Artifact(id.getGroupId(), id.getArtifactId(), id.getVersion(), id.getClassifier(), id.getType());

            Object configs = bundle.getMetadata().get("configurations");
            if (configs instanceof List) {
                for (Object config : (List<?>) configs) {
                    if (config instanceof org.apache.sling.feature.Configuration) {
                        configBundleMap.put((org.apache.sling.feature.Configuration) config, bundle);
                    }
                }
            }

            for(final Map.Entry<String, String> prop : bundle.getMetadata().entrySet()) {
                switch (prop.getKey()) {
                    // these are handled separately
                    case "start-level":
                    case "run-modes":
                        break;
                    default:
                        newBundle.getMetadata().put(prop.getKey(), prop.getValue());
                }
            }

            String[] bundleRunModes = runModes;
            if (bundleRunModes == null) {
                bundleRunModes = getRunModes(bundle);
            }

            int startLevel = bundle.getStartOrder();

            String sl = bundle.getMetadata().get("start-level");
            // special handling for :boot or :launchpad
            if ( sl != null && sl.startsWith(":") ) {
                if ( bundleRunModes != null ) {
                    LOGGER.error(
                            "Unable to convert feature {}. Run modes must not be defined for bundles with start-level {}",
                            provModel.getName(), sl);
                    System.exit(1);
                }

                Feature addFeat = additionalFeatures.get(sl);
                if ( addFeat == null ) {
                    addFeat = new Feature(sl);
                    additionalFeatures.put(sl, addFeat);
                }
                addFeat.getOrCreateRunMode(null).getOrCreateArtifactGroup(0).add(newBundle);
            } else {
                if (startLevel == 0) {
                    if (sl != null) {
                        startLevel = Integer.parseInt(sl);
                    } else {
                        startLevel = 20;
                    }
                }

                provModel.getOrCreateRunMode(bundleRunModes).getOrCreateArtifactGroup(startLevel).add(newBundle);
            }
        }

        // configurations
        for (final org.apache.sling.feature.Configuration cfg : feature.getConfigurations()) {
            final Configuration c;

            List<String> runModeList = new ArrayList<>();
            if (org.apache.sling.feature.Configuration.isFactoryConfiguration(cfg.getPid())) {
                String name = decodeRunModes(org.apache.sling.feature.Configuration.getName(cfg.getPid()), runModeList);
                c = new Configuration(name, org.apache.sling.feature.Configuration.getFactoryPid(cfg.getPid()));
            } else {
                String pid = decodeRunModes(cfg.getPid(), runModeList);
                c = new Configuration(pid, null);
            }
            final Enumeration<String> keys = cfg.getConfigurationProperties().keys();
            while ( keys.hasMoreElements() ) {
                String key = keys.nextElement();
                Object val = cfg.getProperties().get(key);

                if (key.startsWith("..")) {
                    key = ":" + key.substring(2);
                }
                c.getProperties().put(key, val);
            }
            String[] cfgRunModes = runModes;
            if (cfgRunModes == null) {
                cfgRunModes = runModeList.toArray(new String[] {});
                if (cfgRunModes.length == 0)
                    cfgRunModes = null;
            }
            provModel.getOrCreateRunMode(cfgRunModes).getConfigurations().add(c);
        }

        // framework properties
        for (final Map.Entry<String, String> prop : feature.getFrameworkProperties().entrySet()) {
            String key = prop.getKey();
            int idx = key.indexOf(".runmodes:");

            if (idx > 0) {
                String rm = key.substring(idx + ".runmodes:".length());
                String[] runmodes = rm.split(",");
                key = key.substring(0, idx);
                provModel.getOrCreateRunMode(runmodes).getSettings().put(key, prop.getValue());
            } else {
                provModel.getOrCreateRunMode(null).getSettings().put(key, prop.getValue());
            }
        }

        // extensions: content packages and repoinit
        for (final Extension ext : feature.getExtensions()) {
            if (Extension.EXTENSION_NAME_CONTENT_PACKAGES.equals(ext.getName())) {
                for(final org.apache.sling.feature.Artifact cp : ext.getArtifacts() ) {
                    String[] extRunModes = runModes;
                    final ArtifactId id = cp.getId();
                    final Artifact newCP = new Artifact(id.getGroupId(), id.getArtifactId(), id.getVersion(), id.getClassifier(), id.getType());
                    for(final Map.Entry<String, String> prop : cp.getMetadata().entrySet()) {
                        if (prop.getKey().equals("runmodes")) {
                            if (extRunModes == null) {
                                extRunModes = prop.getValue().split(",");
                            }
                        } else {
                            newCP.getMetadata().put(prop.getKey(), prop.getValue());
                        }
                    }
                    provModel.getOrCreateRunMode(extRunModes).getOrCreateArtifactGroup(20).add(newCP);
                }

            } else if (Extension.EXTENSION_NAME_REPOINIT.equals(ext.getName())) {
                final String repoinitContents;
                if (ext.getType() == ExtensionType.TEXT) {
                    repoinitContents = ext.getText();
                } else if (ext.getType() == ExtensionType.JSON) {
                    JsonReader reader = Json.createReader(new StringReader(ext.getJSON()));
                    JsonArray arr = reader.readArray();
                    StringBuilder sb = new StringBuilder();
                    for (JsonValue v : arr) {
                        if (v instanceof JsonString) {
                            sb.append(((JsonString) v).getString());
                            sb.append('\n');
                        }
                    }
                    repoinitContents = sb.toString();
                } else {
                    repoinitContents = null;
                    LOGGER.error("Unable to convert repoinit extension with artifacts");
                    System.exit(1);
                }

                if (runModes == null) {
                    final Section section = new Section("repoinit");
                    section.setContents(repoinitContents);
                    provModel.getAdditionalSections().add(section);

                } else {
                    // create a factory configuration with repoinit
                    // create an name from the featureFileName
                    int lastDot = featureFileName.lastIndexOf('.');
                    String name = lastDot == -1 ? featureFileName : featureFileName.substring(0, lastDot);
                    name = name.replace('-', '_');

                    final RunMode runMode = provModel.getOrCreateRunMode(runModes);
                    final Configuration repoinitCfg = new Configuration(name,
                            "org.apache.sling.jcr.repoinit.RepositoryInitializer");
                    repoinitCfg.getProperties().put("scripts", repoinitContents);
                    runMode.getConfigurations().add(repoinitCfg);
                }
            } else if ( ext.getState() == ExtensionState.REQUIRED ) {
                LOGGER.error("Unable to convert required extension {}", ext.getName());
                System.exit(1);
            }
        }

        final String out = outputFile;
        final File file = new File(out);
        final Model m = new Model();
        m.getFeatures().add(provModel);
        for(final Feature addFeat : additionalFeatures.values()) {
            m.getFeatures().add(addFeat);
        }
        try ( final FileWriter writer = new FileWriter(file)) {
            ModelWriter.write(writer, m);
        } catch ( final IOException ioe) {
            LOGGER.error("Unable to write feature to {} : {}", out, ioe.getMessage(), ioe);
            System.exit(1);
        }
    }

    private static String decodeRunModes(String pid, List<String> runModes) {
        pid = pid.replaceAll("[.][.](\\w+)", ":$1");
        int rmIdx = pid.indexOf(".runmodes.");
        if (rmIdx > 0) {
            String rm = pid.substring(rmIdx + ".runmodes.".length());
            pid = pid.substring(0, rmIdx);
            runModes.addAll(Arrays.asList(rm.split("[.]")));
        }
        return pid;
    }

    private static String[] getRunModes(final org.apache.sling.feature.Artifact bundle) {
        String runMode = bundle.getMetadata().get("run-modes");
        String[] runModes;
        if (runMode != null) {
            runModes = runMode.split(",");
        } else {
            runModes = null;
        }
        return runModes;
    }
}
