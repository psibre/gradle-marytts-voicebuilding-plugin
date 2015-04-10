package de.dfki.mary.voicebuilding

import groovy.json.JsonBuilder
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.logging.LogLevel
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.MavenPlugin
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.bundling.Zip

import groovy.xml.*

import de.dfki.mary.voicebuilding.tasks.legacy.LegacyVoiceImportTask

import marytts.LocalMaryInterface
import marytts.cart.CART
import marytts.cart.LeafNode
import marytts.cart.io.MaryCARTWriter
import marytts.cart.io.WagonCARTReader
import marytts.features.FeatureProcessorManager
import marytts.unitselection.data.FeatureFileReader
import marytts.unitselection.data.TimelineReader
import marytts.unitselection.data.UnitFileReader
import marytts.util.dom.DomUtils

class VoicebuildingPlugin implements Plugin<Project> {

    @Override
    void apply(Project project) {
        project.plugins.apply JavaPlugin
        project.plugins.apply MavenPlugin

        project.sourceCompatibility = JavaVersion.VERSION_1_7

        project.ext {
            maryttsVersion = '5.1.2'
            generatedSrcDir = "$project.buildDir/generated-src"
            generatedTestSrcDir = "$project.buildDir/generated-test-src"
            legacyBuildDir = "$project.buildDir/mary"
            new ConfigSlurper().parse(project.file('voice.groovy').text).each { key, value ->
                set key, value
            }
            voice.nameCamelCase = voice.name?.split(/[^_A-Za-z0-9]/).collect { it.capitalize() }.join()
            voice.locale = voice.locale?.country ? new Locale(voice.locale.language, voice.locale.country) : new Locale(voice.locale.language)
            voice.localeXml = [voice.locale.language, voice.locale.country].join('-')
            voice.maryLocaleXml = voice.locale.language.equalsIgnoreCase(voice.locale.country) ? voice.locale.language : voice.localeXml
            basenames = project.rootProject.subprojects.findAll { it.parent.name == 'data' }.collect { it.name }
        }

        project.status = project.version.endsWith('SNAPSHOT') ? 'integration' : 'release'

        project.repositories {
            jcenter()
            maven {
                url 'http://oss.jfrog.org/artifactory/repo'
            }
        }

        project.configurations.create 'legacy'

        project.sourceSets {
            main {
                java {
                    srcDir project.generatedSrcDir
                }
            }
            test {
                java {
                    srcDir project.generatedTestSrcDir
                }
            }
        }

        project.jar.manifest {
            attributes('Created-By': "${System.properties['java.version']} (${System.properties['java.vendor']})",
                    'Built-By': System.properties['user.name'],
                    'Built-With': "gradle-${project.gradle.gradleVersion}, groovy-${GroovySystem.version}")
        }

        project.task('templates') {
            outputs.files([
                    'Config.java',
                    'ConfigTest.java',
                    'database.config',
                    'LoadVoiceIT.java',
                    'voice.config',
                    'voice-hsmm.config'
            ].collect {
                project.file "$temporaryDir/$it"
            })
            doLast {
                outputs.files.each { outputFile ->
                    outputFile.withOutputStream { stream ->
                        stream << getClass().getResourceAsStream("/de/dfki/mary/voicebuilding/templates/$outputFile.name")
                    }
                }
            }
        }

        project.afterEvaluate {
            project.dependencies {
                compile "de.dfki.mary:marytts-lang-$project.voice.locale.language:$project.maryttsVersion"
                legacy("de.dfki.mary:marytts-builder:$project.maryttsVersion") {
                    exclude module: 'mwdumper'
                    exclude module: 'sgt'
                }
                testCompile "junit:junit:4.11"
            }

            addTasks(project)

            if (project.voice.type == 'unit selection') {
                project.sourceSets.create('data')
                project.sourceSets.test.compileClasspath += project.sourceSets.data.output

                project.processResources {
                    def resourceFileNames = source.files.collect { it.name }
                    [
                            'cart.mry'                             : 'legacyCARTBuilder',
                            'dur.tree'                             : 'processCarts',
                            'f0.left.tree'                         : 'processCarts',
                            'f0.mid.tree'                          : 'processCarts',
                            'f0.right.tree'                        : 'processCarts',
                            'halfphoneUnitFeatureDefinition_ac.txt': 'generateFeatureFiles',
                            'joinCostWeights.txt'                  : 'generateFeatureFiles'
                    ].each { fileName, taskName ->
                        if (!resourceFileNames.contains(fileName)) {
                            dependsOn taskName
                            from project.legacyBuildDir, {
                                include fileName
                            }
                        }
                    }
                }

                project.processDataResources {
                    def resourceFileNames = source.files.collect { it.name }
                    [
                            'halfphoneFeatures_ac.mry': 'legacyAcousticFeatureFileWriter',
                            'halfphoneUnits.mry'      : 'legacyHalfPhoneUnitfileWriter',
                            'joinCostFeatures.mry'    : 'legacyJoinCostFileMaker',
                            'timeline_basenames.mry'  : 'legacyBasenameTimelineMaker',
                            'timeline_waveforms.mry'  : 'legacyWaveTimelineMaker'
                    ].each { fileName, taskName ->
                        if (!resourceFileNames.contains(fileName)) {
                            dependsOn taskName
                            from project.legacyBuildDir, {
                                include fileName
                            }
                        }
                    }
                    rename {
                        "lib/voices/$project.voice.name/$it"
                    }
                }

                project.test.systemProperty 'mary.base', project.sourceSets.data.output.resourcesDir

                project.legacyComponentZip {
                    from project.processDataResources
                }

                project.task('dataZip', type: Zip) {
                    from project.processDataResources
                    classifier 'data'
                }

                project.artifacts {
                    archives project.dataZip
                }
            }
        }

        project.task('run', type: JavaExec) {
            description 'Runs a local MaryTTS server instance with this voice loaded'
            group 'Verification'
            workingDir project.buildDir
            classpath project.configurations.runtime, project.jar
            main 'marytts.server.Mary'
            systemProperties System.properties
            if (logger.isEnabled(LogLevel.INFO)) {
                systemProperties['log4j.logger.marytts'] = 'INFO,stderr'
            }
            if (logger.isEnabled(LogLevel.DEBUG)) {
                systemProperties['log4j.logger.marytts'] = 'DEBUG,stderr'
            }
            project.afterEvaluate {
                if (project.sourceSets.findByName('data')) {
                    dependsOn project.processDataResources
                    systemProperties['mary.base'] = project.sourceSets.data.output.resourcesDir
                }
            }
        }

        project.task('legacyComponentZip', type: Zip) {
            from(project.jar) {
                rename {
                    "lib/$it"
                }
            }
        }
    }

    private void addTasks(Project project) {

        project.task('configurePraat') {
            def proc = 'which praat'.execute()
            proc.waitFor()
            project.ext.praat = proc.in.text
        }

        project.task('configureSpeechTools') {
            def proc = 'which ch_track'.execute()
            proc.waitFor()
            project.ext.speechToolsDir = new File(proc.in.text)?.parentFile?.parent
        }

        project.task('configureHTK') {
            def proc = 'which HRest'.execute()
            proc.waitFor()
            project.ext.htkDir = new File(proc.in.text)?.parent
        }

        project.task('configureEhmm') {
            def proc = 'which ehmm'.execute()
            proc.waitFor()
            project.ext.ehmmDir = new File(proc.in.text)?.parentFile?.parent
        }

        project.task('legacyInit', type: Copy) {
            description "Initialize DatabaseLayout for legacy VoiceImportTools"
            from project.templates
            into project.buildDir
            include 'database.config'
            expand project.properties
            doLast {
                project.file(project.legacyBuildDir).mkdirs()
            }
        }

        project.task('legacyPraatPitchmarker', type: LegacyVoiceImportTask) {
            dependsOn project.legacyInit, project.configurePraat
            inputs.files project.basenames.collect { "$project.buildDir/wav/${it}.wav" }
            outputs.files project.basenames.collect { "$project.buildDir/pm/${it}.pm" }
        }

        project.task('legacyMCEPMaker', type: LegacyVoiceImportTask) {
            dependsOn project.legacyInit, project.configureSpeechTools
            inputs.files project.legacyPraatPitchmarker
            outputs.files project.basenames.collect { "$project.buildDir/mcep/${it}.mcep" }
        }

        project.task('generateAllophones') {
            dependsOn project.legacyInit
            inputs.files project.basenames.collect { "$project.buildDir/text/${it}.txt" }
            def destDir = project.file("$project.buildDir/prompt_allophones")
            outputs.files project.basenames.collect { "$destDir/${it}.xml" }
            doFirst {
                destDir.mkdirs()
            }
            doLast {
                def parser = new XmlSlurper(false, false)
                def mary = new LocalMaryInterface()
                mary.locale = project.voice.locale
                mary.outputType = 'ALLOPHONES'
                [inputs.files as List, outputs.files as List].transpose().each { inFile, outFile ->
                    def doc = mary.generateXML(inFile.text)
                    def xmlStr = XmlUtil.serialize(doc.documentElement)
                    def xml = parser.parseText(xmlStr)
                    outFile.text = XmlUtil.serialize(xml)
                }
            }
        }

        project.task('legacyHTKLabeler', type: LegacyVoiceImportTask) {
            dependsOn project.legacyInit, project.configureHTK
            inputs.files project.fileTree("$project.buildDir/wav").include('*.wav'), project.generateAllophones
            outputs.files project.fileTree("$project.buildDir/htk/lab").include('*.lab')
        }

        project.task('legacyEHMMLabeler', type: LegacyVoiceImportTask) {
            dependsOn project.legacyInit, project.configureEhmm
            inputs.files project.fileTree("$project.buildDir/wav").include('*.wav'), project.generateAllophones
            outputs.files project.fileTree("$project.buildDir/ehmm/lab").include('*.lab')
        }

        project.task('legacyLabelPauseDeleter', type: LegacyVoiceImportTask) {
            inputs.files project.legacyEHMMLabeler
            outputs.files inputs.files.collect {
                new File("$project.buildDir/lab", it.name)
            }
        }

        project.task('legacyPhoneUnitLabelComputer', type: LegacyVoiceImportTask) {
            dependsOn project.legacyInit
            inputs.files project.basenames.collect { "$project.buildDir/lab/${it}.lab" }
            outputs.files project.basenames.collect { "$project.buildDir/phonelab/${it}.lab" }
        }

        project.task('legacyHalfPhoneUnitLabelComputer', type: LegacyVoiceImportTask) {
            dependsOn project.legacyInit
            inputs.files project.basenames.collect { "$project.buildDir/lab/${it}.lab" }
            outputs.files project.basenames.collect { "$project.buildDir/halfphonelab/${it}.hplab" }
        }

        project.task('legacyTranscriptionAligner', type: LegacyVoiceImportTask) {
            inputs.files project.generateAllophones, project.basenames.collect { "$project.buildDir/lab/${it}.lab" }
            outputs.files project.basenames.collect { "$project.buildDir/allophones/${it}.xml" }
        }

        project.task('generateFeatureList') {
            dependsOn project.legacyInit
            ext.featureFile = project.file("$project.legacyBuildDir/features.txt")
            outputs.files featureFile
            doLast {
                def fpm
                try {
                    fpm = Class.forName("marytts.language.${project.voice.locale.language}.features.FeatureProcessorManager").newInstance()
                } catch (ClassNotFoundException e) {
                    logger.warn "Reflection failed: $e"
                    logger.warn "Instantiating generic FeatureProcessorManager for locale $project.voice.locale"
                    fpm = new FeatureProcessorManager(project.voice.locale)
                }
                def featureNames = fpm.listByteValuedFeatureProcessorNames().tokenize() + fpm.listShortValuedFeatureProcessorNames().tokenize()
                featureFile.text = featureNames.join('\n')
            }
        }

        project.task('generatePhoneUnitFeatures') {
            dependsOn project.legacyInit, project.generateFeatureList
            inputs.files project.legacyTranscriptionAligner
            outputs.files project.basenames.collect { "$project.buildDir/phonefeatures/${it}.pfeats" }
            def mary
            doFirst {
                mary = new LocalMaryInterface()
                mary.locale = project.voice.locale
                mary.inputType = 'ALLOPHONES'
                mary.outputType = 'TARGETFEATURES'
                def features = project.generateFeatureList.featureFile.readLines() - ['phone', 'halfphone_lr', 'halfphone_unitname']
                mary.outputTypeParams = "phone ${features.join(' ')}"
            }
            doLast {
                [inputs.files as List, outputs.files as List].transpose().each { inFile, outFile ->
                    def doc = DomUtils.parseDocument(inFile)
                    outFile.text = mary.generateText(doc)
                }
            }
        }

        project.task('generateHalfPhoneUnitFeatures') {
            dependsOn project.legacyInit, project.generateFeatureList
            inputs.files project.legacyTranscriptionAligner
            outputs.files project.basenames.collect { "$project.buildDir/halfphonefeatures/${it}.hpfeats" }
            def mary
            doFirst {
                mary = new LocalMaryInterface()
                mary.locale = project.voice.locale
                mary.inputType = 'ALLOPHONES'
                mary.outputType = 'HALFPHONE_TARGETFEATURES'
                def features = project.generateFeatureList.featureFile.readLines() - ['halfphone_unitname']
                mary.outputTypeParams = "halfphone_unitname ${features.join(' ')}"
            }
            doLast {
                [inputs.files as List, outputs.files as List].transpose().each { inFile, outFile ->
                    def doc = DomUtils.parseDocument(inFile)
                    outFile.text = mary.generateText(doc)
                }
            }
        }

        project.task('legacyWaveTimelineMaker', type: LegacyVoiceImportTask) {
            inputs.files project.legacyPraatPitchmarker
            ext.timelineFile = new File(project.legacyBuildDir, 'timeline_waveforms.mry')
            outputs.files timelineFile
        }

        project.task('legacyBasenameTimelineMaker', type: LegacyVoiceImportTask) {
            inputs.files project.legacyPraatPitchmarker
            outputs.files new File("$project.legacyBuildDir", 'timeline_basenames.mry')
        }

        project.task('legacyMCepTimelineMaker', type: LegacyVoiceImportTask) {
            dependsOn project.legacyInit
            inputs.files project.legacyPraatPitchmarker, project.legacyMCEPMaker
            outputs.files new File("$project.legacyBuildDir", 'timeline_mcep.mry')
        }

        project.task('legacyPhoneUnitfileWriter', type: LegacyVoiceImportTask) {
            inputs.files project.legacyPraatPitchmarker, project.legacyPhoneUnitLabelComputer
            ext.unitFile = new File(project.legacyBuildDir, 'phoneUnits.mry')
            outputs.files unitFile
        }

        project.task('legacyHalfPhoneUnitfileWriter', type: LegacyVoiceImportTask) {
            inputs.files project.legacyPraatPitchmarker, project.legacyHalfPhoneUnitLabelComputer
            outputs.files new File("$project.legacyBuildDir", 'halfphoneUnits.mry')
        }

        project.task('legacyPhoneFeatureFileWriter', type: LegacyVoiceImportTask) {
            inputs.files project.legacyPhoneUnitfileWriter, project.generatePhoneUnitFeatures
            ext.featureFile = project.file("$project.legacyBuildDir/phoneFeatures.mry")
            outputs.files featureFile, project.file("$project.legacyBuildDir/phoneUnitFeatureDefinition.txt")
        }

        project.task('legacyHalfPhoneFeatureFileWriter', type: LegacyVoiceImportTask) {
            inputs.files project.legacyHalfPhoneUnitfileWriter, project.generateHalfPhoneUnitFeatures
            outputs.files project.files("$project.legacyBuildDir/halfphoneFeatures.mry", "$project.legacyBuildDir/halfphoneUnitFeatureDefinition.txt")
        }

        project.task('legacyF0PolynomialFeatureFileWriter', type: LegacyVoiceImportTask) {
            inputs.files project.legacyHalfPhoneUnitfileWriter, project.legacyWaveTimelineMaker, project.legacyHalfPhoneFeatureFileWriter
            outputs.files project.file("$project.legacyBuildDir/syllableF0Polynomials.mry")
        }

        project.task('legacyAcousticFeatureFileWriter', type: LegacyVoiceImportTask) {
            inputs.files project.legacyHalfPhoneUnitfileWriter, project.legacyF0PolynomialFeatureFileWriter, project.legacyHalfPhoneFeatureFileWriter
            outputs.files project.files("$project.legacyBuildDir/halfphoneFeatures_ac.mry", "$project.legacyBuildDir/halfphoneUnitFeatureDefinition_ac.txt")
        }

        project.task('legacyJoinCostFileMaker', type: LegacyVoiceImportTask) {
            inputs.files project.legacyMCEPMaker, project.legacyMCepTimelineMaker, project.legacyHalfPhoneUnitfileWriter, project.legacyAcousticFeatureFileWriter
            outputs.files project.file("$project.legacyBuildDir/joinCostFeatures.mry")
        }

        project.task('legacyCARTBuilder', type: LegacyVoiceImportTask) {
            inputs.files project.legacyAcousticFeatureFileWriter
            outputs.files project.file("$project.legacyBuildDir/cart.mry")
        }

        project.task('extractDurationFeatures') {
            inputs.files project.legacyPhoneFeatureFileWriter, project.legacyPhoneUnitfileWriter
            ext.featsFile = project.file("$temporaryDir/dur.feats")
            outputs.files featsFile
            doLast {
                def featureFile = FeatureFileReader.getFeatureFileReader(project.legacyPhoneFeatureFileWriter.featureFile.path)
                def featureDefinition = featureFile.featureDefinition
                def unitFile = new UnitFileReader(project.legacyPhoneUnitfileWriter.unitFile.path)
                featsFile.withWriter { feats ->
                    (0..unitFile.numberOfUnits - 1).each { u ->
                        def unit = unitFile.getUnit(u)
                        def samples = unit.duration
                        def duration = samples / unitFile.sampleRate
                        if (duration > 0.01) {
                            def features = featureFile.getFeatureVector(u)
                            feats.println "$duration ${featureDefinition.toFeatureString(features)}"
                        }
                    }
                }
            }
        }

        project.task('generateDurationFeaturesDescription') {
            inputs.files project.legacyPhoneFeatureFileWriter
            ext.descFile = project.file("$temporaryDir/dur.desc")
            outputs.files descFile
            doLast {
                def featureFile = FeatureFileReader.getFeatureFileReader project.legacyPhoneFeatureFileWriter.featureFile.path
                def featureDefinition = featureFile.featureDefinition
                descFile.withWriter { desc ->
                    desc.println '('
                    desc.println '( segment_duration float )'
                    featureDefinition.featureNameArray.eachWithIndex { feature, f ->
                        def values = featureDefinition.getPossibleValues(f)
                        desc.print "( $feature "
                        if (featureDefinition.isContinuousFeature(f) || values.length == 20 && values.last() == '19') {
                            desc.print 'float'
                        } else {
                            desc.print values.collect { "\"${it.replace('"', '\\\"')}\"" }.join(' ')
                        }
                        desc.println " )"
                    }
                    desc.println " )"
                }
            }
        }

        project.task('trainDurationCart', type: Exec) {
            inputs.files project.extractDurationFeatures, project.generateDurationFeaturesDescription
            def treeFile = project.file("$temporaryDir/dur.tree")
            outputs.files treeFile
            dependsOn project.legacyInit, project.configureSpeechTools
            executable "$project.speechToolsDir/bin/wagon"
            args = [
                    '-data', project.extractDurationFeatures.featsFile,
                    '-desc', project.generateDurationFeaturesDescription.descFile,
                    '-stop', 10,
                    '-output', treeFile
            ]
        }

        project.task('extractF0Features') {
            inputs.files project.legacyPhoneFeatureFileWriter, project.legacyPhoneUnitfileWriter, project.legacyWaveTimelineMaker
            ext.leftFeatsFile = project.file("$temporaryDir/f0.left.feats")
            ext.midFeatsFile = project.file("$temporaryDir/f0.mid.feats")
            ext.rightFeatsFile = project.file("$temporaryDir/f0.right.feats")
            outputs.files leftFeatsFile, midFeatsFile, rightFeatsFile
            doLast {
                // open destination files for writing
                def leftFeats = new FileWriter(leftFeatsFile)
                def midFeats = new FileWriter(midFeatsFile)
                def rightFeats = new FileWriter(rightFeatsFile)
                // MaryTTS files needed to extract F0 baked into unit datagram durations
                def featureFile = FeatureFileReader.getFeatureFileReader(project.legacyPhoneFeatureFileWriter.featureFile.path)
                def featureDefinition = featureFile.featureDefinition
                def unitFile = new UnitFileReader(project.legacyPhoneUnitfileWriter.unitFile.path)
                def waveTimeline = new TimelineReader(project.legacyWaveTimelineMaker.timelineFile.path)
                // in the absence of high-level feature value accessors, need feature indices
                def numSegsFromSylStartFeatureIndex = featureDefinition.getFeatureIndex('segs_from_syl_start')
                def numSegsFromEndStartFeatureIndex = featureDefinition.getFeatureIndex('segs_from_syl_end')
                def phoneFeatureIndex = featureDefinition.getFeatureIndex('phone')
                def isVowelFeatureIndex = featureDefinition.getFeatureIndex('ph_vc')
                def isVoicedConsonantFeatureIndex = featureDefinition.getFeatureIndex('ph_cvox')
                // iterate over all units
                for (def u = 0; u < unitFile.numberOfUnits; u++) {
                    def sylSegs = []
                    // in absence of syllable structure, use segment counter features
                    def featureVector = featureFile.getFeatureVector(u)
                    def firstSegInSyl = u + featureVector.getFeatureAsInt(numSegsFromSylStartFeatureIndex)
                    def lastSegInSyl = u + featureVector.getFeatureAsInt(numSegsFromEndStartFeatureIndex)
                    // reconstruct relevant features per segment in syllable
                    (firstSegInSyl..lastSegInSyl).each {
                        featureVector = featureFile.getFeatureVector(it)
                        def phone = featureVector.getFeatureAsString(phoneFeatureIndex, featureDefinition)
                        def isVowel = featureVector.getFeatureAsString(isVowelFeatureIndex, featureDefinition) == '+'
                        def isVoicedConsonant = featureVector.getFeatureAsString(isVoicedConsonantFeatureIndex, featureDefinition) == '+'
                        sylSegs << [
                                'unitIndex': it,
                                'phone'    : phone,
                                'isVoiced' : isVowel || isVoicedConsonant,
                                'isVowel'  : isVowel,
                                'features' : featureVector
                        ]
                    }
                    // proceed to reconstruct F0 values from voiced segments, if any
                    def voicedSegs = sylSegs.grep { it['isVowel'] }
                    if (voicedSegs) {
                        // left F0
                        def firstVoicedSeg = voicedSegs.first()
                        def firstVoicedDatagrams = waveTimeline.getDatagrams(unitFile.getUnit(firstVoicedSeg['unitIndex']), unitFile.sampleRate)
                        def leftF0 = waveTimeline.sampleRate / firstVoicedDatagrams.first().duration
                        leftFeats.println "$leftF0 ${featureDefinition.toFeatureString(firstVoicedSeg['features'])}"
                        // mid F0
                        def firstVowel = voicedSegs.grep { it['isVowel'] }.first()
                        def vowelDatagrams = waveTimeline.getDatagrams(unitFile.getUnit(firstVowel['unitIndex']), unitFile.sampleRate)
                        def midF0 = waveTimeline.sampleRate / vowelDatagrams[vowelDatagrams.length / 2 as int].duration
                        midFeats.println "$midF0 ${featureDefinition.toFeatureString(firstVowel['features'])}"
                        // right F0
                        def lastVoicedSeg = voicedSegs.last()
                        def lastVoicedDatagrams = waveTimeline.getDatagrams(unitFile.getUnit(lastVoicedSeg['unitIndex']), unitFile.sampleRate)
                        def rightF0 = waveTimeline.sampleRate / lastVoicedDatagrams.last().duration
                        rightFeats.println "$rightF0 ${featureDefinition.toFeatureString(lastVoicedSeg['features'])}"
                    }
                    // increment to end of syllable
                    u = lastSegInSyl
                }
                leftFeats.close()
                midFeats.close()
                rightFeats.close()
            }
        }

        project.task('generateF0FeaturesDescription') {
            inputs.files project.legacyPhoneFeatureFileWriter
            ext.descFile = project.file("$temporaryDir/f0.desc")
            outputs.files descFile
            doLast {
                def featureFile = FeatureFileReader.getFeatureFileReader(project.legacyPhoneFeatureFileWriter.featureFile.path)
                def featureDefinition = featureFile.featureDefinition
                descFile.withWriter { desc ->
                    desc.println '('
                    desc.println '( f0 float )'
                    featureDefinition.featureNameArray.eachWithIndex { feature, f ->
                        def values = featureDefinition.getPossibleValues(f)
                        desc.print "( $feature "
                        if (featureDefinition.isContinuousFeature(f) || values.length == 20 && values.last() == '19') {
                            desc.print 'float'
                        } else {
                            desc.print values.collect { "\"${it.replace('"', '\\\"')}\"" }.join(' ')
                        }
                        desc.println " )"
                    }
                    desc.println " )"
                }
            }
        }

        project.task('trainLeftF0Cart', type: Exec) {
            inputs.files project.extractF0Features.leftFeatsFile, project.generateF0FeaturesDescription
            def treeFile = project.file("$temporaryDir/f0.left.tree")
            outputs.files treeFile
            dependsOn project.legacyInit, project.configureSpeechTools, project.extractF0Features
            executable "$project.speechToolsDir/bin/wagon"
            args = [
                    '-data', project.extractF0Features.leftFeatsFile,
                    '-desc', project.generateF0FeaturesDescription.descFile,
                    '-stop', 10,
                    '-output', treeFile
            ]
        }

        project.task('trainMidF0Cart', type: Exec) {
            inputs.files project.extractF0Features.midFeatsFile, project.generateF0FeaturesDescription
            def treeFile = project.file("$temporaryDir/f0.mid.tree")
            outputs.files treeFile
            dependsOn project.legacyInit, project.configureSpeechTools, project.extractF0Features
            executable "$project.speechToolsDir/bin/wagon"
            args = [
                    '-data', project.extractF0Features.midFeatsFile,
                    '-desc', project.generateF0FeaturesDescription.descFile,
                    '-stop', 10,
                    '-output', treeFile
            ]
        }

        project.task('trainRightF0Cart', type: Exec) {
            inputs.files project.extractF0Features.rightFeatsFile, project.generateF0FeaturesDescription
            def treeFile = project.file("$temporaryDir/f0.right.tree")
            outputs.files treeFile
            dependsOn project.legacyInit, project.configureSpeechTools, project.extractF0Features
            executable "$project.speechToolsDir/bin/wagon"
            args = [
                    '-data', project.extractF0Features.rightFeatsFile,
                    '-desc', project.generateF0FeaturesDescription.descFile,
                    '-stop', 10,
                    '-output', treeFile
            ]
        }

        project.task('processCarts') {
            inputs.files project.trainDurationCart, project.trainLeftF0Cart, project.trainMidF0Cart, project.trainRightF0Cart
            outputs.files inputs.files.collect {
                new File(project.legacyBuildDir, it.name)
            }
            dependsOn project.legacyPhoneFeatureFileWriter
            doLast {
                def featureFile = FeatureFileReader.getFeatureFileReader(project.legacyPhoneFeatureFileWriter.featureFile.path)
                def featureDefinition = featureFile.featureDefinition
                [inputs.files as List, outputs.files as List].transpose().each { inFile, outFile ->
                    def wagonCartReader = new WagonCARTReader(LeafNode.LeafType.FloatLeafNode)
                    def rootNode = wagonCartReader.load(new BufferedReader(new FileReader(inFile)), featureDefinition)
                    def cart = new CART(rootNode, featureDefinition)
                    def maryCartWriter = new MaryCARTWriter()
                    maryCartWriter.dumpMaryCART(cart, outFile.path);
                }
            }
        }

        project.task('generateSource', type: Copy) {
            from project.templates
            into project.generatedSrcDir
            include 'Config.java'
            rename {
                "marytts/voice/$project.voice.nameCamelCase/$it"
            }
            expand project.properties
        }

        project.compileJava.dependsOn project.generateSource

        project.task('generateTestSource', type: Copy) {
            from project.templates
            into project.generatedTestSrcDir
            include 'ConfigTest.java', 'LoadVoiceIT.java'
            rename {
                "marytts/voice/$project.voice.nameCamelCase/$it"
            }
            expand project.properties
        }

        project.compileTestJava.dependsOn project.generateTestSource

        project.task('generateServiceLoader') {
            def serviceLoaderFile = project.file("$project.sourceSets.main.output.resourcesDir/META-INF/services/marytts.config.MaryConfig")
            outputs.files serviceLoaderFile
            doFirst {
                serviceLoaderFile.parentFile.mkdirs()
            }
            doLast {
                serviceLoaderFile.text = "marytts.voice.${project.voice.nameCamelCase}.Config"
            }
        }

        project.task('generateVoiceConfig', type: Copy) {
            from project.templates
            into project.sourceSets.main.output.resourcesDir
            include project.voice.type == 'hsmm' ? 'voice-hsmm.config' : 'voice.config'
            rename {
                "marytts/voice/$project.voice.nameCamelCase/voice.config"
            }
            expand project.properties
        }

        project.task('generateFeatureFiles') {
            def destDir = project.file("$project.sourceSets.main.output.resourcesDir/marytts/voice/$project.voice.nameCamelCase")
            def featureFile = new File(destDir, 'halfphoneUnitFeatureDefinition_ac.txt')
            def joinCostFile = new File(destDir, 'joinCostWeights.txt')
            outputs.files featureFile, joinCostFile
            doFirst {
                destDir.mkdirs()
            }
            doLast {
                try {
                    project.apply from: 'weights.gradle'
                    def fpm
                    try {
                        fpm = Class.forName("marytts.language.${project.voice.language}.features.FeatureProcessorManager").newInstance()
                    } catch (e) {
                        logger.info "Reflection failed: $e"
                        logger.info "Instantiating generic FeatureProcessorManager for locale $project.voice.locale"
                        fpm = new FeatureProcessorManager(project.voice.locale)
                    }
                    featureFile.withWriter { dest ->
                        dest.println 'ByteValuedFeatureProcessors'
                        fpm.listByteValuedFeatureProcessorNames().tokenize().sort { a, b ->
                            if (a == 'halfphone_unitname') return -1
                            if (b == 'halfphone_unitname') return 1
                            a <=> b
                        }.each { name ->
                            def weight = project.featureWeights[name] ?: 0
                            def values = fpm.getFeatureProcessor(name).values.join(' ')
                            dest.println "$weight | $name $values"
                        }
                        dest.println 'ShortValuedFeatureProcessors'
                        fpm.listShortValuedFeatureProcessorNames().tokenize().each { name ->
                            def weight = project.featureWeights[name] ?: 0
                            def values = fpm.getFeatureProcessor(name).values.join(' ')
                            dest.println "$weight | $name $values"
                        }
                        dest.println 'ContinuousFeatureProcessors'
                        fpm.listContinuousFeatureProcessorNames().tokenize().each { name ->
                            def weight = project.featureWeights[name] ?: 0
                            dest.println "$weight | $name"
                        }
                    }
                    joinCostFile.withWriter { dest ->
                        (0..13).each { name ->
                            def weight = project.featureWeights[name] ?: '1.0 linear'
                            dest.println "${name.toString().padRight(2)} : $weight"
                        }
                    }
                } catch (e) {
                    logger.warn "No weights definition found -- assuming resources are provided..."
                }
            }
        }

        project.processResources {
            rename {
                "marytts/voice/$project.voice.nameCamelCase/$it"
            }
            dependsOn project.generateServiceLoader, project.generateVoiceConfig
        }

        project.test {
            systemProperty 'log4j.logger.marytts', 'DEBUG,stderr'
            maxHeapSize = '1g'
        }

        project.task('generatePom') {
            def pomDir = project.file("${project.sourceSets.main.output.resourcesDir}/META-INF/maven/${project.group.replace('.', '/')}/$project.name")
            def pomFile = project.file("$pomDir/pom.xml")
            def propFile = project.file("$pomDir/pom.properties")
            outputs.files project.files(pomFile, propFile)
            doFirst {
                pomDir.mkdirs()
            }
            doLast {
                project.pom { pom ->
                    pom.project {
                        description project.voice.description
                        licenses {
                            license {
                                name project.voice.license.name
                                url project.voice.license.url
                            }
                        }
                    }
                }.writeTo(pomFile)
                propFile.withWriter { dest ->
                    dest.println "version=$project.version"
                    dest.println "groupId=$project.group"
                    dest.println "artifactId=$project.name"
                }
            }
        }

        project.jar.dependsOn project.generatePom

        project.task('legacyComponentXml') {
            dependsOn project.legacyComponentZip
            def zipFile = project.legacyComponentZip.outputs.files.singleFile
            def xmlFile = project.file("$project.distsDir/$project.name-$project.version-component.xml")
            inputs.files zipFile
            outputs.files xmlFile
            doLast {
                ant.checksum(file: zipFile, property: "${zipFile.name}.md5")
                def zipFileHash = ant.properties["${zipFile.name}.md5"]
                def builder = new StreamingMarkupBuilder()
                def xml = builder.bind {
                    'marytts-install'(xmlns: 'http://mary.dfki.de/installer') {
                        voice(gender: project.voice.gender, locale: project.voice.localeXml, name: project.voice.name, type: project.voice.type, version: project.version) {
                            delegate.description project.voice.description
                            license(href: project.voice.license.url)
                            'package'(filename: zipFile.name, md5sum: zipFileHash, size: zipFile.size()) {
                                location(folder: true, href: "http://mary.dfki.de/download/$project.maryttsVersion/")
                            }
                            depends(language: project.voice.localeXml, version: project.maryttsVersion)
                        }
                    }
                }
                xmlFile.text = XmlUtil.serialize(xml)
            }
        }

        project.task('generateJsonDescriptor') {
            def jsonFile = new File(project.distsDir, "$project.name-${project.version}.json")
            inputs.files project.uploadArchives.artifacts
            outputs.files jsonFile
            doFirst {
                project.distsDir.mkdirs()
            }
            doLast {
                def json = new JsonBuilder()
                json {
                    'group' project.group
                    'artifact' project.name
                    'version' project.version
                    'name' project.voice.name
                    'language' project.voice.locale.language
                    'gender' project.voice.gender
                    'type' project.voice.type
                    'description' project.voice.description
                    'license' {
                        'name' project.voice.license.name
                        'url' project.voice.license.url
                    }
                    'files' inputs.files.collectEntries {
                        ant.checksum(file: it, algorithm: 'SHA-1', property: "${it.name}.sha1")
                        [(it.name): ['size': it.size(),
                                     'sha1': ant.properties["${it.name}.sha1"]]]
                    }
                }
                jsonFile.text = json.toPrettyString()
            }
        }

        project.task('visualizeTaskDependencyGraph') << {
            project.file('build.dot').withWriter { dot ->
                dot.println 'digraph G {'
                dot.println 'rankdir=BT;'
                dot.println 'node [shape=box];'
                dot.println 'edge [dir="back"];'
                project.tasks.each { task ->
                    dot.println "$task.name;"
                    task.taskDependencies.getDependencies(task).each { otherTask ->
                        dot.println "$task.name -> $otherTask.name;"
                    }
                }
                dot.println "}"
            }
        }
    }
}
