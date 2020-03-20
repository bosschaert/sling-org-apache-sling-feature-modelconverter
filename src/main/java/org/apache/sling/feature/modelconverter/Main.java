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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
    name = "pm2fm",
    description = "Apache Sling Provisioning Model to Sling Feature Model converter",
    footer = "Copyright(c) 2019-2020 The Apache Software Foundation."
)
public class Main implements Runnable {

    public static final String PACKAGING_FEATURE = "slingosgifeature";

    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

    @Option(names = { "-h", "--help" }, usageHelp = true, description = "Display the usage message")
    private boolean helpRequested;

    @Option(names = { "-i", "--provisining-input-directory" }, description = "The input directory where the Provisioning File are", required = true)
    private File provisioningModelsInputDirectory;

    @Option(names = { "-o", "--features-output-directory" }, description = "The output directory where the Feature File will be generated in", required = true)
    private File featureModelsOutputDirectory;

    @Option(names = { "-g", "--group-id" }, description = "Overwriting the Group Id of the Model ID")
    private String groupId;

    @Option(names = { "-v", "--version" }, description = "Overwriting the Version of the Model ID")
    private String version;

    @Option(names = { "-V", "--useProvidedVersion" }, description = "If flagged then the provided version will override any given version from Provisioning Model")
    private boolean useProvidedVersion;

    @Option(names = { "-n", "--name" }, description = "Sets a General Name for all converted Models. This also means that the name is placed in the classifier")
    private String name;

    @Option(names = { "-d", "--dropVariable" }, description = "Variable (by name) in a Feature Model to be excluded (repeat for more)")
    private List<String> dropVariables;

    @Option(names = { "-a", "--addFrameworkProperty" }, description = "Adds Framework Property to Feature Models. Format: <Model Name>:<Property Name>=<value> (repeat for more)")
    private List<String> addFrameworkProperties;

    @Option(names = { "-D", "--noProvisioningModelName" }, description = "If flagged then the Provisioning Model Name is not added")
    private boolean noProvisioningModelName;

    @Option(names = { "-e", "--excludeBundle" }, description = "Bundle and/or Bundle Configuration to be excluded (repeat for more)")
    private List<String> excludeBundles;

    @Option(names = { "-r", "--runMode" }, description = "Runmode to add to this build (all no-runmodes are included by default, repeat for more)")
    private List<String> runModes;

    private Pattern pattern = Pattern.compile("^(.*?):(.*?)=(.*?)$");

    @Override
    public void run() {
        try {
            if(!provisioningModelsInputDirectory.isDirectory()) { throw new IllegalArgumentException("Input Folder is not a directory"); }
            if(!provisioningModelsInputDirectory.canRead()) { throw new IllegalArgumentException("Input Folder is not readable"); }
            if(!featureModelsOutputDirectory.exists()) {
                File parent = featureModelsOutputDirectory.getParentFile();
                if(parent == null || !parent.exists()) { throw new IllegalArgumentException("Parent Folder (" + parent + ") of output folder (" + featureModelsOutputDirectory + ") does not exist"); }
                featureModelsOutputDirectory.mkdir();
            }
            if(!featureModelsOutputDirectory.isDirectory()) { throw new IllegalArgumentException("Output Folder is not a directory"); }
            if(!featureModelsOutputDirectory.canWrite()) { throw new IllegalArgumentException("Output Folder is not writable"); }
            List<File> provisioningFiles = Arrays.asList(provisioningModelsInputDirectory.listFiles());
            Map<String,Object> options = new HashMap<>();
            if(groupId != null && !groupId.isEmpty()) { options.put("groupId", groupId); }
            if(version != null && !version.isEmpty()) { options.put("version", version); }
            // Todo: do we have a way to check the name?
            if(name != null && !name.isEmpty()) { options.put("name", name); }
            LOGGER.info("Use Provided Version Flag: '{}'", useProvidedVersion);
            options.put("useProvidedVersion", useProvidedVersion);
            options.put("noProvisioningModelName", noProvisioningModelName);
            options.put("dropVariables", dropVariables);
            Map<String,Map<String,String>> frameworkPropertiesMap = new HashMap<>();
            for(String value: addFrameworkProperties) {
                // Separate Model from Property Name Value pair. Create Sub Map if needed and then add
                LOGGER.info("Check Add Framework Properties Line: '{}'", value);
                Matcher matcher = pattern.matcher(value);
                LOGGER.info("Pattern Group Matches: '{}', Count: '{}'", matcher.matches(), matcher.groupCount());
                if(matcher.matches() && matcher.groupCount() == 3) {
                    String modelName = matcher.group(1);
                    String propName = matcher.group(2);
                    String propValue = matcher.group(3);
                    LOGGER.info("Model Name: '{}', Prop Name: '{}', Value: '{}'", modelName, propName, propValue);
                    Map<String,String> modelMap = frameworkPropertiesMap.get(modelName);
                    if(modelMap == null) {
                        modelMap = new HashMap<>();
                        frameworkPropertiesMap.put(modelName, modelMap);
                    }
                    modelMap.put(propName, propValue);
                }
            }
            options.put("addFrameworkProperties", frameworkPropertiesMap);
            options.put("excludeBundles", excludeBundles);
            LOGGER.info("Excluded Bundles: '{}'", excludeBundles);
            options.put("runModes", runModes == null ? new ArrayList<>() : runModes);
            LOGGER.info("Runmodes: '{}'", runModes);

            // Start the Conversion
            for(File file: provisioningFiles) {
                LOGGER.info("Handle File: '{}'", file.getAbsolutePath());
                ProvisioningToFeature.convert(file, featureModelsOutputDirectory, options);
            }
        } catch(Throwable t) {
            LOGGER.error("Failed to Convert", t);
        }
    }

    public static void main(String[] args) {
        CommandLine.run(new Main(), args);
    }
}
