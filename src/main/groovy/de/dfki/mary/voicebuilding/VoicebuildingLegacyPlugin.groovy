package de.dfki.mary.voicebuilding

import de.dfki.mary.voicebuilding.tasks.*
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.bundling.Zip

class VoicebuildingLegacyPlugin implements Plugin<Project> {

    @Override
    void apply(Project project) {

        project.plugins.apply VoicebuildingDataPlugin

        project.sourceSets.create 'legacy'

        project.configurations.create 'legacy'

        project.ext {
            legacyBuildDir = project.layout.buildDirectory.dir('mary')

            // configure speech-tools
            def proc = 'which ch_track'.execute()
            proc.waitFor()
            speechToolsDir = new File(proc.in.text)?.parentFile?.parent
        }

        project.templates {
            resources.add '/de/dfki/mary/voicebuilding/templates/database.config'
        }

        def legacyInitTask = project.task('legacyInit', type: LegacyInitTask) {
            srcDir = project.tasks.getByName('templates').destDir
            configFile = project.layout.buildDirectory.file('database.config')
        }

        def basenamesTask = project.tasks.getByName('basenames')

        def legacyPhoneUnitLabelComputerTask = project.task('legacyPhoneUnitLabelComputer', type: LegacyVoiceImportTask) {
            srcDir = project.layout.buildDirectory.dir('lab')
            destDir = project.layout.buildDirectory.dir('phonelab_unaligned')
            doLast {
                project.copy {
                    from "$project.buildDir/phonelab"
                    into destDir
                }
            }
        }

        def legacyHalfPhoneUnitLabelComputerTask = project.task('legacyHalfPhoneUnitLabelComputer', type: LegacyVoiceImportTask) {
            srcDir = project.layout.buildDirectory.dir('lab')
            destDir = project.layout.buildDirectory.dir('halfphonelab_unaligned')
            doLast {
                project.copy {
                    from "$project.buildDir/halfphonelab"
                    into destDir
                }
            }
        }

        def legacyTranscriptionAlignerTask = project.task('legacyTranscriptionAligner', type: LegacyVoiceImportTask) {
            srcDir = project.layout.buildDirectory.dir('lab')
            srcDir2 = project.tasks.getByName('generateAllophones').destDir
            destDir = project.layout.buildDirectory.dir('allophones')
        }

        project.task('featureLister', type: FeatureListerTask) {
            destFile = project.legacyBuildDir.get().file('features.txt')
        }

        def phoneUnitFeatureComputerTask = project.task('phoneUnitFeatureComputer', type: MaryInterfaceBatchTask) {
            dependsOn project.featureLister
            srcDir = legacyTranscriptionAlignerTask.destDir
            destDir = project.layout.buildDirectory.dir('phonefeatures')
            inputType = 'ALLOPHONES'
            inputExt = 'xml'
            outputType = 'TARGETFEATURES'
            outputExt = 'pfeats'
            doFirst {
                outputTypeParams = ['phone'] + project.featureLister.destFile.get().asFile.readLines().findAll {
                    it != 'phone' && !(it in ['halfphone_lr', 'halfphone_unitname'])
                }
            }
        }

        def halfPhoneUnitFeatureComputerTask = project.task('halfPhoneUnitFeatureComputer', type: MaryInterfaceBatchTask) {
            dependsOn project.featureLister
            srcDir = legacyTranscriptionAlignerTask.destDir
            destDir = project.layout.buildDirectory.dir('halfphonefeatures')
            inputType = 'ALLOPHONES'
            inputExt = 'xml'
            outputType = 'HALFPHONE_TARGETFEATURES'
            outputExt = 'hpfeats'
            doFirst {
                outputTypeParams = ['halfphone_unitname'] + project.featureLister.destFile.get().asFile.readLines().findAll {
                    it != 'halfphone_unitname'
                }
            }
        }

        def legacyWaveTimelineMakerTask = project.task('legacyWaveTimelineMaker', type: LegacyVoiceImportTask) {
            srcDir = project.layout.buildDirectory.dir('wav')
            srcDir2 = project.tasks.getByName('pitchmarkConverter').destDir
            destFile = project.legacyBuildDir.get().file('timeline_waveforms.mry')
        }

        project.task('legacyBasenameTimelineMaker', type: LegacyVoiceImportTask) {
            srcDir = project.layout.buildDirectory.dir('wav')
            srcDir2 = project.tasks.getByName('pitchmarkConverter').destDir
            destFile = project.legacyBuildDir.get().file('timeline_basenames.mry')
        }

        def legacyMCepTimelineMakerTask = project.task('legacyMCepTimelineMaker', type: LegacyVoiceImportTask) {
            srcDir = project.layout.buildDirectory.dir('wav')
            srcDir2 = project.tasks.getByName('mcepExtractor').destDir
            destFile = project.legacyBuildDir.get().file('timeline_mcep.mry')
        }

        def legacyPhoneLabelFeatureAlignerTask = project.task('legacyPhoneLabelFeatureAligner', type: LegacyVoiceImportTask) {
            srcDir = legacyPhoneUnitLabelComputerTask.destDir
            srcDir2 = phoneUnitFeatureComputerTask.destDir
            destDir = project.layout.buildDirectory.dir('phonelab_aligned')
            doLast {
                project.copy {
                    from "$project.buildDir/phonelab"
                    into destDir
                }
            }
        }

        def legacyHalfPhoneLabelFeatureAlignerTask = project.task('legacyHalfPhoneLabelFeatureAligner', type: LegacyVoiceImportTask) {
            srcDir = legacyHalfPhoneUnitLabelComputerTask.destDir
            srcDir2 = halfPhoneUnitFeatureComputerTask.destDir
            destDir = project.layout.buildDirectory.dir('halfphonelab_aligned')
            doLast {
                project.copy {
                    from "$project.buildDir/halfphonelab"
                    into destDir
                }
            }
        }

        def legacyPhoneUnitfileWriterTask = project.task('legacyPhoneUnitfileWriter', type: LegacyVoiceImportTask) {
            srcDir = project.tasks.getByName('pitchmarkConverter').destDir
            srcDir2 = legacyPhoneUnitLabelComputerTask.destDir
            srcDir3 = legacyPhoneLabelFeatureAlignerTask.destDir
            destFile = project.legacyBuildDir.get().file('phoneUnits.mry')
        }

        def legacyHalfPhoneUnitfileWriterTask = project.task('legacyHalfPhoneUnitfileWriter', type: LegacyVoiceImportTask) {
            srcDir = project.tasks.getByName('pitchmarkConverter').destDir
            srcDir2 = legacyHalfPhoneUnitLabelComputerTask.destDir
            srcDir3 = legacyHalfPhoneLabelFeatureAlignerTask.destDir
            destFile = project.legacyBuildDir.get().file('halfphoneUnits.mry')
        }

        def legacyPhoneFeatureFileWriterTask = project.task('legacyPhoneFeatureFileWriter', type: LegacyVoiceImportTask) {
            srcFile = legacyPhoneUnitfileWriterTask.destFile
            srcDir = phoneUnitFeatureComputerTask.destDir
            destFile = project.legacyBuildDir.get().file('phoneFeatures.mry')
            destFile2 = project.legacyBuildDir.get().file('phoneUnitFeatureDefinition.txt')
        }

        def legacyHalfPhoneFeatureFileWriterTask = project.task('legacyHalfPhoneFeatureFileWriter', type: LegacyVoiceImportTask) {
            srcFile = legacyHalfPhoneUnitfileWriterTask.destFile
            srcDir = halfPhoneUnitFeatureComputerTask.destDir
            destFile = project.legacyBuildDir.get().file('halfphoneFeatures.mry')
            destFile2 = project.legacyBuildDir.get().file('halfphoneUnitFeatureDefinition.txt')
        }

        def legacyF0PolynomialFeatureFileWriterTask = project.task('legacyF0PolynomialFeatureFileWriter', type: LegacyVoiceImportTask) {
            srcFile = legacyHalfPhoneUnitfileWriterTask.destFile
            srcFile2 = legacyWaveTimelineMakerTask.destFile
            srcFile3 = legacyHalfPhoneFeatureFileWriterTask.destFile
            destFile = project.legacyBuildDir.get().file('syllableF0Polynomials.mry')
        }

        def legacyAcousticFeatureFileWriterTask = project.task('legacyAcousticFeatureFileWriter', type: LegacyVoiceImportTask) {
            srcFile = legacyHalfPhoneUnitfileWriterTask.destFile
            srcFile2 = legacyF0PolynomialFeatureFileWriterTask.destFile
            srcFile3 = legacyHalfPhoneFeatureFileWriterTask.destFile
            destFile = project.legacyBuildDir.get().file('halfphoneFeatures_ac.mry')
            destFile2 = project.legacyBuildDir.get().file('halfphoneUnitFeatureDefinition_ac.txt')
        }

        project.task('legacyJoinCostFileMaker', type: LegacyVoiceImportTask) {
            srcFile = legacyMCepTimelineMakerTask.destFile
            srcFile2 = legacyHalfPhoneUnitfileWriterTask.destFile
            srcFile3 = legacyAcousticFeatureFileWriterTask.destFile
            destFile = project.legacyBuildDir.get().file('joinCostFeatures.mry')
            destFile2 = project.legacyBuildDir.get().file('joinCostWeights.txt')
        }

        project.task('legacyCARTBuilder', type: LegacyVoiceImportTask) {
            srcFile = legacyAcousticFeatureFileWriterTask.destFile
            destFile = project.legacyBuildDir.get().file('cart.mry')
        }

        project.task('generateDurationFeatureDescription', type: GenerateProsodyFeatureDescription) {
            srcFile = legacyPhoneFeatureFileWriterTask.destFile
            targetFeatures = ['segment_duration']
            destFile = project.layout.buildDirectory.dir('prosody').get().file('dur.desc')
        }

        project.task('generateF0FeatureDescription', type: GenerateProsodyFeatureDescription) {
            srcFile = legacyPhoneFeatureFileWriterTask.destFile
            targetFeatures = ['leftF0', 'midF0', 'rightF0']
            destFile = project.layout.buildDirectory.dir('prosody').get().file('f0.desc')
        }

        project.task('extractDurationFeatures', type: ExtractDurationFeatures) {
            unitFile = legacyPhoneUnitfileWriterTask.destFile
            featureFile = legacyPhoneFeatureFileWriterTask.destFile
            destFile = project.layout.buildDirectory.dir('prosody').get().file('dur.feats')
        }

        project.task('extractF0Features', type: ExtractF0Features) {
            unitFile = legacyPhoneUnitfileWriterTask.destFile
            featureFile = legacyPhoneFeatureFileWriterTask.destFile
            timelineFile = legacyWaveTimelineMakerTask.destFile
            destFile = project.layout.buildDirectory.dir('prosody').get().file('f0.feats')
        }

        project.task('trainDurationCart', type: TrainProsodyCart) {
            dataFile = project.extractDurationFeatures.destFile
            descriptionFile = project.generateDurationFeatureDescription.destFile
            predictee = 'segment_duration'
            destFile = project.layout.buildDirectory.dir('prosody').get().file('dur.tree')
        }

        project.task('trainF0LeftCart', type: TrainProsodyCart) {
            dataFile = project.extractF0Features.destFile
            descriptionFile = project.generateF0FeatureDescription.destFile
            predictee = 'leftF0'
            ignoreFields = ['midF0', 'rightF0']
            destFile = project.layout.buildDirectory.dir('prosody').get().file('f0.left.tree')
        }

        project.task('trainF0MidCart', type: TrainProsodyCart) {
            dataFile = project.extractF0Features.destFile
            descriptionFile = project.generateF0FeatureDescription.destFile
            predictee = 'midF0'
            ignoreFields = ['leftF0', 'rightF0']
            destFile = project.layout.buildDirectory.dir('prosody').get().file('f0.mid.tree')
        }

        project.task('trainF0RightCart', type: TrainProsodyCart) {
            dataFile = project.extractF0Features.destFile
            descriptionFile = project.generateF0FeatureDescription.destFile
            predictee = 'rightF0'
            ignoreFields = ['leftF0', 'midF0']
            destFile = project.layout.buildDirectory.dir('prosody').get().file('f0.right.tree')
        }

        project.task('convertDurationCart', type: ConvertProsodyCart) {
            srcFile = project.trainDurationCart.destFile
            featureFile = legacyPhoneFeatureFileWriterTask.destFile
            destFile = project.legacyBuildDir.get().file('dur.tree')
        }

        project.task('convertF0LeftCart', type: ConvertProsodyCart) {
            srcFile = project.trainF0LeftCart.destFile
            featureFile = legacyPhoneFeatureFileWriterTask.destFile
            destFile = project.legacyBuildDir.get().file('f0.left.tree')
        }

        project.task('convertF0MidCart', type: ConvertProsodyCart) {
            srcFile = project.trainF0MidCart.destFile
            featureFile = legacyPhoneFeatureFileWriterTask.destFile
            destFile = project.legacyBuildDir.get().file('f0.mid.tree')
        }

        project.task('convertF0RightCart', type: ConvertProsodyCart) {
            srcFile = project.trainF0RightCart.destFile
            featureFile = legacyPhoneFeatureFileWriterTask.destFile
            destFile = project.legacyBuildDir.get().file('f0.right.tree')
        }

        project.tasks.withType(LegacyVoiceImportTask) {
            configFile = legacyInitTask.configFile
            basenamesFile = basenamesTask.destFile
        }

        project.generateVoiceConfig {
            project.afterEvaluate {
                config.get() << [
                        'viterbi.wTargetCosts'    : 0.7,
                        'viterbi.beamsize'        : 100,
                        databaseClass             : 'marytts.unitselection.data.DiphoneUnitDatabase',
                        selectorClass             : 'marytts.unitselection.select.DiphoneUnitSelector',
                        concatenatorClass         : 'marytts.unitselection.concat.OverlapUnitConcatenator',
                        targetCostClass           : 'marytts.unitselection.select.DiphoneFFRTargetCostFunction',
                        joinCostClass             : 'marytts.unitselection.select.JoinCostFeatures',
                        unitReaderClass           : 'marytts.unitselection.data.UnitFileReader',
                        cartReaderClass           : 'marytts.cart.io.MARYCartReader',
                        audioTimelineReaderClass  : 'marytts.unitselection.data.TimelineReader',
                        featureFile               : "MARY_BASE/lib/voices/$project.marytts.voice.name/halfphoneFeatures_ac.mry",
                        targetCostWeights         : "jar:/marytts/voice/$project.marytts.voice.nameCamelCase/halfphoneUnitFeatureDefinition_ac.txt",
                        joinCostFile              : "MARY_BASE/lib/voices/$project.marytts.voice.name/joinCostFeatures.mry",
                        joinCostWeights           : "jar:/marytts/voice/$project.marytts.voice.nameCamelCase/joinCostWeights.txt",
                        unitsFile                 : "MARY_BASE/lib/voices/$project.marytts.voice.name/halfphoneUnits.mry",
                        cartFile                  : "jar:/marytts/voice/$project.marytts.voice.nameCamelCase/cart.mry",
                        audioTimelineFile         : "MARY_BASE/lib/voices/$project.marytts.voice.name/timeline_waveforms.mry",
                        basenameTimeline          : "MARY_BASE/lib/voices/$project.marytts.voice.name/timeline_basenames.mry",
                        acousticModels            : 'duration F0 midF0 rightF0',
                        'duration.model'          : 'cart',
                        'duration.data'           : "jar:/marytts/voice/$project.marytts.voice.nameCamelCase/dur.tree",
                        'duration.attribute'      : 'd',
                        'F0.model'                : 'cart',
                        'F0.data'                 : "jar:/marytts/voice/$project.marytts.voice.nameCamelCase/f0.left.tree",
                        'F0.attribute'            : 'f0',
                        'F0.attribute.format'     : '(0,%.0f)',
                        'F0.predictFrom'          : 'firstVowels',
                        'F0.applyTo'              : 'firstVoicedSegments',
                        'midF0.model'             : 'cart',
                        'midF0.data'              : "jar:/marytts/voice/$project.marytts.voice.nameCamelCase/f0.mid.tree",
                        'midF0.attribute'         : 'f0',
                        'midF0.attribute.format'  : '(50,%.0f)',
                        'midF0.predictFrom'       : 'firstVowels',
                        'midF0.applyTo'           : 'firstVowels',
                        'rightF0.model'           : 'cart',
                        'rightF0.data'            : "jar:/marytts/voice/$project.marytts.voice.nameCamelCase/f0.right.tree",
                        'rightF0.attribute'       : 'f0',
                        'rightF0.attribute.format': '(100,%.0f)',
                        'rightF0.predictFrom'     : 'firstVowels',
                        'rightF0.applyTo'         : 'lastVoicedSegments'
                ]
            }
        }

        project.processResources {
            from project.legacyAcousticFeatureFileWriter, {
                include 'halfphoneUnitFeatureDefinition_ac.txt'
            }
            from project.legacyJoinCostFileMaker, {
                include 'joinCostWeights.txt'
            }
            from project.legacyCARTBuilder
            from project.convertDurationCart
            from project.convertF0LeftCart
            from project.convertF0MidCart
            from project.convertF0RightCart
            project.afterEvaluate {
                rename { "marytts/voice/$project.marytts.voice.nameCamelCase/$it" }
            }
        }

        project.processLegacyResources {
            from project.legacyWaveTimelineMaker
            from project.legacyBasenameTimelineMaker
            from project.legacyHalfPhoneUnitfileWriter
            from project.legacyAcousticFeatureFileWriter, {
                include 'halfphoneFeatures_ac.mry'
            }
            from project.legacyJoinCostFileMaker, {
                include 'joinCostFeatures.mry'
            }
            project.afterEvaluate {
                rename { "lib/voices/$project.marytts.voice.name/$it" }
            }
        }

        project.run {
            dependsOn project.processLegacyResources
            systemProperty 'mary.base', project.sourceSets.legacy.output.resourcesDir
        }

        project.integrationTest {
            dependsOn project.processLegacyResources
            systemProperty 'mary.base', project.sourceSets.legacy.output.resourcesDir
        }

        def legacyZipTask = project.task('legacyZip', type: LegacyZip) {
            from project.processLegacyResources
            from project.jar, {
                rename { "lib/$it" }
            }
            destFile = project.layout.fileProperty()
        }

        def legacyDescriptorTask = project.task('legacyDescriptor', type: LegacyDescriptorTask) {
            project.assemble.dependsOn it
            srcFile = legacyZipTask.destFile
            destFile = project.layout.fileProperty()
        }

        project.artifacts {
            'default' legacyZipTask
        }

        project.publishing {
            publications {
                mavenJava {
                    artifact legacyZipTask
                }
            }
        }

        project.afterEvaluate {
            project.dependencies {
                compile "de.dfki.mary:marytts-lang-$project.marytts.voice.language:$project.marytts.version", {
                    exclude group: '*', module: 'groovy-all'
                }
                legacy("de.dfki.mary:marytts-builder:$project.marytts.version") {
                    exclude group: '*', module: 'mwdumper'
                    exclude group: '*', module: 'sgt'
                }
                testCompile "junit:junit:4.12"
            }

            // TODO: legacyZip.archiveName is modified (version is infixed), so we need to update
            def distsDir = project.layout.buildDirectory.dir(project.distsDirName)
            legacyZipTask.destFile.set(distsDir.get().file(legacyZipTask.archiveName))
            legacyDescriptorTask.destFile.set(distsDir.get().file(legacyZipTask.archiveName.replace('.zip', '-component-descriptor.xml')))
        }
    }
}
