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
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.sling.feature.ArtifactId;
import org.apache.sling.feature.Bundles;
import org.apache.sling.feature.Configurations;
import org.apache.sling.feature.Extension;
import org.apache.sling.feature.ExtensionType;
import org.apache.sling.feature.Extensions;
import org.apache.sling.feature.io.artifacts.ArtifactHandler;
import org.apache.sling.feature.io.artifacts.ArtifactManager;
import org.apache.sling.feature.io.artifacts.ArtifactManagerConfig;
import org.apache.sling.feature.io.json.FeatureJSONWriter;
import org.apache.sling.provisioning.model.Artifact;
import org.apache.sling.provisioning.model.ArtifactGroup;
import org.apache.sling.provisioning.model.Configuration;
import org.apache.sling.provisioning.model.Feature;
import org.apache.sling.provisioning.model.MergeUtility;
import org.apache.sling.provisioning.model.Model;
import org.apache.sling.provisioning.model.ModelConstants;
import org.apache.sling.provisioning.model.ModelUtility;
import org.apache.sling.provisioning.model.ModelUtility.ResolverOptions;
import org.apache.sling.provisioning.model.ModelUtility.VariableResolver;
import org.apache.sling.provisioning.model.RunMode;
import org.apache.sling.provisioning.model.Section;
import org.apache.sling.provisioning.model.Traceable;
import org.apache.sling.provisioning.model.io.ModelReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Converter that converts the provisioning model to the feature model.
 */
public class ProvisioningToFeature {
    private static Logger LOGGER = LoggerFactory.getLogger(ProvisioningToFeature.class);

    public static List<File> convert(File file, File outDir, Map<String, Object> options) {
        Model model = createModel(Collections.singletonList(file), null, true, false);

        String bareFileName = getBareFileName(file);
        final List<org.apache.sling.feature.Feature> features = buildFeatures(model, bareFileName, options);

        List<File> files = new ArrayList<>();
        for (org.apache.sling.feature.Feature f : features) {
            String id = f.getVariables().get(FeatureToProvisioning.PROVISIONING_MODEL_NAME_VARIABLE);
            if (id == null) {
                id = f.getId().getArtifactId();
            }
            id = id.replaceAll("[:]","");
            id = bareFileName + "_" + id;

            boolean noProvisioningModelName = getOption(options, "noProvisioningModelName", false);
            if(noProvisioningModelName) {
                // Provisioning Model Names create a conflict is provided in multiple PM files so it is dropped by request
                f.getVariables().remove(FeatureToProvisioning.PROVISIONING_MODEL_NAME_VARIABLE);
            }

            File outFile = new File(outDir, id + ".json");
            files.add(outFile);

            if (outFile.exists()) {
                // On a very fast computer the generated file might have the same timestamp if the file was previously copied
                if (outFile.lastModified() >= file.lastModified()) {
                    LOGGER.debug("Skipping the generation of {} as this file already exists and is not older.", outFile);
                    continue;
                } else {
                    LOGGER.debug("Deleting existing file {} as source is newer, modified: out: '{}', source: '{}'", outFile, outFile.lastModified(), file.lastModified());
                    outFile.delete();
                }
            }

            writeFeature(f, outFile.getAbsolutePath(), 0);
        }
        return files;
    }

    public static void convert(List<File> files,  String outputFile, String runModes, boolean createApp,
            boolean includeModelInfo, String propsFile) {
        final Model model = createModel(files, runModes, false, includeModelInfo);

        final List<org.apache.sling.feature.Feature> features = buildFeatures(model, null, Collections.emptyMap());
        int index = 1;
        for(final org.apache.sling.feature.Feature feature : features) {
            writeFeature(feature, outputFile, features.size() > 1 ? index : 0);
            index++;
        }
    }

    /**
     * Read the models and prepare the model
     * @param files The model files
     * @param includeModelInfo
     */
    private static Model createModel(final List<File> files,
            final String runModes, boolean allRunModes, boolean includeModelInfo) {
        LOGGER.info("Assembling model...");
        ResolverOptions variableResolver = new ResolverOptions().variableResolver(new VariableResolver() {
            @Override
            public String resolve(final Feature feature, final String name) {
                // Keep variables as-is in the model
                return "${" + name + "}";
            }
        });

        Model model = null;
        for(final File initFile : files) {
            try {
                model = processModel(model, initFile.toURI().toURL(), includeModelInfo, variableResolver);
            } catch ( final IOException iae) {
                LOGGER.error("Unable to read provisioning model {} : {}", initFile, iae.getMessage(), iae);
                System.exit(1);
            }
        }

        final Model effectiveModel = ModelUtility.getEffectiveModel(model, variableResolver);
        final Map<Traceable, String> errors = ModelUtility.validate(effectiveModel);
        if ( errors != null ) {
            LOGGER.error("Invalid assembled provisioning model.");
            for(final Map.Entry<Traceable, String> entry : errors.entrySet()) {
                LOGGER.error("- {} : {}", entry.getKey().getLocation(), entry.getValue());
            }
            System.exit(1);
        }
        final Set<String> modes;
        if (allRunModes) {
            modes = new HashSet<>();
            for (Feature f : effectiveModel.getFeatures()) {
                for (RunMode rm : f.getRunModes()) {
                    String[] names = rm.getNames();
                    if (names != null) {
                        modes.addAll(Arrays.asList(names));
                    }
                }
            }
        } else {
            modes = calculateRunModes(effectiveModel, runModes);
        }

        return effectiveModel;
    }

    /**
     * Process the given model and merge it into the provided model
     * @param model The already read model
     * @param modelFile The model file
     * @param includeModelInfo
     * @return The merged model
     * @throws IOException If reading fails
     */
    private static Model processModel(Model model,
            URL modelFile, boolean includeModelInfo) throws IOException {
        return processModel(model, modelFile, includeModelInfo,
            new ResolverOptions().variableResolver(new VariableResolver() {
                @Override
                public String resolve(final Feature feature, final String name) {
                    return name;
                }
            })
        );
    }

    private static Model processModel(Model model,
            URL modelFile, boolean includeModelInfo, ResolverOptions options) throws IOException {
        LOGGER.info("- reading model {}", modelFile);

        final Model nextModel = readProvisioningModel(modelFile);

        final Model effectiveModel = ModelUtility.getEffectiveModel(nextModel, options);
        for(final Feature feature : effectiveModel.getFeatures()) {
            for(final RunMode runMode : feature.getRunModes()) {
                for(final ArtifactGroup group : runMode.getArtifactGroups()) {
                    final List<org.apache.sling.provisioning.model.Artifact> removeList = new ArrayList<>();
                    for(final org.apache.sling.provisioning.model.Artifact a : group) {
                        if ( "slingstart".equals(a.getType())
                             || "slingfeature".equals(a.getType())) {

                            final ArtifactManagerConfig cfg = new ArtifactManagerConfig();
                            final ArtifactManager mgr = ArtifactManager.getArtifactManager(cfg);

                            final ArtifactId correctedId = new ArtifactId(a.getGroupId(),
                                    a.getArtifactId(),
                                    a.getVersion(),
                                    "slingstart".equals(a.getType()) ? "slingfeature" : a.getClassifier(),
                                    "txt");

                            final ArtifactHandler handler = mgr.getArtifactHandler(correctedId.toMvnUrl());
                            model = processModel(model, handler.getLocalURL(), includeModelInfo);

                            removeList.add(a);
                        } else {
                            final org.apache.sling.provisioning.model.Artifact realArtifact = nextModel.getFeature(feature.getName()).getRunMode(runMode.getNames()).getArtifactGroup(group.getStartLevel()).search(a);

                            if ( includeModelInfo ) {
                                realArtifact.getMetadata().put("model-filename", modelFile.getPath().substring(modelFile.getPath().lastIndexOf("/") + 1));
                            }
                            if ( runMode.getNames() != null ) {
                                realArtifact.getMetadata().put("runmodes", String.join(",", runMode.getNames()));
                            }
                        }
                    }
                    for(final org.apache.sling.provisioning.model.Artifact r : removeList) {
                        nextModel.getFeature(feature.getName()).getRunMode(runMode.getNames()).getArtifactGroup(group.getStartLevel()).remove(r);
                    }
                }
            }
        }

        if ( model == null ) {
            model = nextModel;
        } else {
            MergeUtility.merge(model, nextModel);
        }
        return model;
    }

    /**
     * Read the provisioning model
     */
    private static Model readProvisioningModel(final URL file)
    throws IOException {
        try (final Reader is = new InputStreamReader(file.openStream(), "UTF-8")) {
            return ModelReader.read(is, file.getPath());
        }
    }

    private static Set<String> calculateRunModes(final Model model, final String runModes) {
        final Set<String> modesSet = new HashSet<>();

        // check configuration property first
        if (runModes != null && runModes.trim().length() > 0) {
            final String[] modes = runModes.split(",");
            for(int i=0; i < modes.length; i++) {
                modesSet.add(modes[i].trim());
            }
        }

        //  handle configured options
        final Feature feature = model.getFeature(ModelConstants.FEATURE_BOOT);
        if ( feature != null ) {
            handleOptions(modesSet, feature.getRunMode().getSettings().get("sling.run.mode.options"));
            handleOptions(modesSet, feature.getRunMode().getSettings().get("sling.run.mode.install.options"));
        }

        return modesSet;
    }

    private static void handleOptions(final Set<String> modesSet, final String propOptions) {
        if ( propOptions != null && propOptions.trim().length() > 0 ) {

            final String[] options = propOptions.trim().split("\\|");
            for(final String opt : options) {
                String selected = null;
                final String[] modes = opt.trim().split(",");
                for(int i=0; i<modes.length; i++) {
                    modes[i] = modes[i].trim();
                    if ( selected != null ) {
                        modesSet.remove(modes[i]);
                    } else {
                        if ( modesSet.contains(modes[i]) ) {
                            selected = modes[i];
                        }
                    }
                }
                if ( selected == null ) {
                    selected = modes[0];
                    modesSet.add(modes[0]);
                }
            }
        }
    }

    private static void buildFromFeature(final Feature feature,
            final Map<String,String> variables,
            final List<String> dropVariables,
            final Bundles bundles,
            final List<String> excludeBundles,
            final Configurations configurations,
            final List<String> currentRunModes,
            final Extensions extensions,
            final Map<String,String> properties) {
        for (Iterator<Map.Entry<String, String>> it = feature.getVariables().iterator(); it.hasNext(); ) {
            Entry<String, String> entry = it.next();
            boolean found = false;
            if(dropVariables != null) {
                for (String variableName : dropVariables) {
                    // Look if variable is in the list of Variables to be dropped
                    if (entry.getKey().equals(variableName)) {
                        found = true;
                    }
                }
            }
            if(!found) { variables.put(entry.getKey(), entry.getValue()); }
        }

        Extension cpExtension = extensions.getByName(Extension.EXTENSION_NAME_CONTENT_PACKAGES);
        for(final RunMode runMode : feature.getRunModes() ) {
            int runModelFilteringMode = 0; // Default behavior with no filtering
            String[] runModeNames = runMode.getNames();
            if(!currentRunModes.isEmpty()) {
                if(runModeNames == null || runModeNames.length == 0) {
                    runModelFilteringMode = 1; // No Runmode configured -> write as usual
                } else {
                    for(String runModeName: runModeNames) {
                        if(currentRunModes.contains(runModeName)) {
                            runModelFilteringMode = 2; // Matching Runmode -> write out w/o run mode suffix
                        }
                    }
                    if(runModelFilteringMode != 2) {
                        runModelFilteringMode = -1; // Ignore this runmode as it does not have a match
                    }
                }
            }
            if(runModelFilteringMode < 0) {
                continue;
            }
            for(final ArtifactGroup group : runMode.getArtifactGroups()) {
                for(final Artifact artifact : group) {
                    ArtifactId id = ArtifactId.fromMvnUrl(artifact.toMvnUrl());
                    String artifactId = id.getArtifactId();
                    LOGGER.info("Check Artitfact Id: '{}' if excluded by: '{}'", artifactId, excludeBundles);
                    if(excludeBundles.contains(artifactId)) {
                        // If bundle is excluded then go to the next one
                        continue;
                    }
                    String version = id.getVersion();
                    if(version.startsWith("${") && version.endsWith("}")) {
                        // Replace Variable with value if found
                        String versionFromVariable = variables.get(version.substring(2, version.length() - 1));
                        if (versionFromVariable != null && !versionFromVariable.isEmpty()) {
                            id = new ArtifactId(id.getGroupId(), id.getArtifactId(), versionFromVariable, id.getClassifier(), id.getType());
                        }
                    }
                    final org.apache.sling.feature.Artifact newArtifact = new org.apache.sling.feature.Artifact(id);

                    for(final Map.Entry<String, String> entry : artifact.getMetadata().entrySet()) {
                        newArtifact.getMetadata().put(entry.getKey(), entry.getValue());
                    }

                    if ( newArtifact.getId().getType().equals("zip") ) {
                        if ( cpExtension == null ) {
                            cpExtension = new Extension(ExtensionType.ARTIFACTS,
                                    Extension.EXTENSION_NAME_CONTENT_PACKAGES, true);
                            extensions.add(cpExtension);
                        }
                        cpExtension.getArtifacts().add(newArtifact);
                    } else {
                        int startLevel = group.getStartLevel();
                        if ( startLevel == 0) {
                            if ( ModelConstants.FEATURE_BOOT.equals(feature.getName()) ) {
                                startLevel = 1;
                            } else if ( startLevel == 0 ) {
                                startLevel = 20;
                            }
                        }
                        newArtifact.getMetadata().put("start-order", String.valueOf(startLevel));

                        bundles.add(newArtifact);
                    }
                }
            }

            for(final Configuration cfg : runMode.getConfigurations()) {
                String pid = cfg.getPid();
                if (pid.startsWith(":")) {
                    // The configurator doesn't accept colons ':' in it's keys, so replace these
                    pid = ".." + pid.substring(1);
                }

                LOGGER.info("Check Configuration Id: '{}' if excluded by: '{}'", pid, excludeBundles);
                if(excludeBundles.contains(pid)) {
                    // If configuration is excluded then go to the next one
                    continue;
                }

                if (runModeNames != null && runModelFilteringMode != 2) {
                    pid = pid + ".runmodes." + String.join(".", runModeNames);
                    pid = pid.replaceAll("[:]", "..");
                }

                final org.apache.sling.feature.Configuration newCfg;
                if ( cfg.getFactoryPid() != null ) {
                    newCfg = new org.apache.sling.feature.Configuration(cfg.getFactoryPid() + '~' + pid);
                } else {
                    newCfg = new org.apache.sling.feature.Configuration(pid);
                }
                final Enumeration<String> keys = cfg.getProperties().keys();
                while ( keys.hasMoreElements() ) {
                    String key = keys.nextElement();
                    Object value = cfg.getProperties().get(key);

                    if (key.startsWith(":")) {
                        key = ".." + key.substring(1);
                    }
                    newCfg.getProperties().put(key, value);
                }

                configurations.add(newCfg);
            }

            for(final Map.Entry<String, String> prop : runMode.getSettings()) {
                if (runModeNames == null && runModelFilteringMode != 2) {
                    properties.put(prop.getKey(), prop.getValue());
                } else {
                    properties.put(prop.getKey() + ".runmodes:" + String.join(",", runModeNames),
                            prop.getValue());
                }
            }
        }

        final StringBuilder repoinitText = new StringBuilder();
        for(final Section sect : feature.getAdditionalSections("repoinit")) {
            repoinitText.append(sect.getContents()).append("\n");
        }

        if(repoinitText.length() > 0) {
            Extension repoExtension = extensions.getByName(Extension.EXTENSION_NAME_REPOINIT);

            if ( repoExtension == null ) {
                // TODO: As of now only TEXT is accepted for Repoinit -> verify and adjust 
//                repoExtension = new Extension(ExtensionType.JSON, Extension.EXTENSION_NAME_REPOINIT, true);
                repoExtension = new Extension(ExtensionType.TEXT, Extension.EXTENSION_NAME_REPOINIT, true);
                extensions.add(repoExtension);
//                repoExtension.setText(textToJSON(repoinitText.toString()));
                repoExtension.setText(repoinitText.toString());
            } else {
                throw new IllegalStateException("Repoinit sections already processed");
            }
        }
}

    private static String textToJSON(String text) {
        text = text.replace('\t', ' ');
        String[] lines = text.split("[\n]");

        StringBuilder sb = new StringBuilder();
        sb.append('[');

        boolean first = true;
        for (String t : lines) {
            if (first)
                first = false;
            else
                sb.append(',');

            sb.append('"');
            sb.append(t);
            sb.append('"');
        }
        sb.append(']');
        return sb.toString();
    }

    private static List<org.apache.sling.feature.Feature> buildFeatures(Model model, String bareFileName, Map<String, Object> options) {
        final List<org.apache.sling.feature.Feature> features = new ArrayList<>();

        String groupId = getOption(options, "groupId", "generated");
        String version = getOption(options, "version", "1.0.0");
        String nameOption = getOption(options, "name", "");
        boolean useProvidedVersion = getOption(options, "useProvidedVersion", false);
        List<String> dropVariables = getOption(options, "dropVariables", new ArrayList<>());
        List<String> excludeBundles = getOption(options, "excludeBundles", new ArrayList<>());
        Map<String,Map<String,String>> addFrameworkProperties = getOption(options, "addFrameworkProperties", new HashMap<String,Map<String, String>>());
        List<String> runModes = getOption(options, "runModes", new ArrayList<>());

        for(final Feature feature : model.getFeatures() ) {
            final String idString;
            String name = feature.getName();
            if (name == null) { name = "feature"; }
            name = name.replaceAll("[:]", "");

            if (!"feature".equals(name) && !name.equals(bareFileName)) {
                name = bareFileName + "_" + name;
            }

            // Todo: shouldn't a provided Version overwrite the Feature Version ?
            if ( feature.getVersion() != null && !useProvidedVersion ) {
                version = feature.getVersion();
            }

            // When providing a classifier a type must be provided and so we set it to 'slingfeature'
            idString =
                groupId + "/" +
                (nameOption.isEmpty() ? name : nameOption) + "/" +
                version +
                (nameOption.isEmpty() ? "" :
                    "/slingfeature/" + name );
            final org.apache.sling.feature.Feature f = new org.apache.sling.feature.Feature(ArtifactId.parse(idString));
            features.add(f);

            Map<String,String> variables = f.getVariables();
            if(dropVariables != null) {
                for (String variableName : dropVariables) {
                    if (variables.containsKey(variableName)) {
                        variables.remove(variableName);
                    }
                }
            }
            Map<String,String> frameworkProperties = f.getFrameworkProperties();
            String simpleName = feature.getName().replaceAll("[:]", "");
            if(addFrameworkProperties.containsKey(simpleName)) {
                Map<String,String> modelFeatureProperties = addFrameworkProperties.get(simpleName);
                if(modelFeatureProperties != null) {
                    for(Entry<String,String> entry: modelFeatureProperties.entrySet()) {
                        frameworkProperties.put(entry.getKey(), entry.getValue());
                    }
                }
            }
            buildFromFeature(feature, variables, dropVariables, f.getBundles(), excludeBundles, f.getConfigurations(), runModes, f.getExtensions(), frameworkProperties);

            if (!f.getId().getArtifactId().equals(feature.getName())) {
                f.getVariables().put(FeatureToProvisioning.PROVISIONING_MODEL_NAME_VARIABLE, feature.getName());
            }
        }

        return features;
    }

    private static String getBareFileName(File file) {
        String bareFileName = file.getName();
        int idx = bareFileName.lastIndexOf('.');
        if (idx > 0) {
            bareFileName = bareFileName.substring(0, idx);
        }
        return bareFileName;
    }

    @SuppressWarnings("unchecked")
    private static <T> T getOption(Map<String, Object> options, String name, T defaultValue) {
        if (options.containsKey(name)) {
            return (T) options.get(name);
        } else {
            return defaultValue;
        }
    }

    private static void writeFeature(final org.apache.sling.feature.Feature f, String out, final int index) {
        if ( index > 0 ) {
            final int lastDot = out.lastIndexOf('.');
            if ( lastDot == -1 ) {
                out = out + "_" + String.valueOf(index);
            } else {
                out = out.substring(0, lastDot) + "_" + String.valueOf(index) + out.substring(lastDot);
            }
        }

        LOGGER.info("to file {}", out);
        final File file = new File(out);
        while (file.exists()) {
            LOGGER.error("Output file already exists: {}", file.getAbsolutePath());
            System.exit(1);
        }

        try ( final FileWriter writer = new FileWriter(file)) {
            FeatureJSONWriter.write(writer, f);
        } catch ( final IOException ioe) {
            LOGGER.error("Unable to write feature to {} : {}", out, ioe.getMessage(), ioe);
            System.exit(1);
        }
    }
}
