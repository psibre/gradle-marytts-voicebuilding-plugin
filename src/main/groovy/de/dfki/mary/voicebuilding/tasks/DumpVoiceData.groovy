package de.dfki.mary.voicebuilding.tasks

import marytts.unitselection.analysis.VoiceDataDumper
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

class DumpVoiceData extends DefaultTask {

    @InputFile
    final RegularFileProperty waveTimelineFile = project.objects.fileProperty()

    @InputFile
    final RegularFileProperty basenameTimelineFile = project.objects.fileProperty()

    @InputFile
    final RegularFileProperty halfPhoneUnitFile = project.objects.fileProperty()

    @InputFile
    final RegularFileProperty acousticFeatureFile = project.objects.fileProperty()

    @OutputFile
    final RegularFileProperty textGridFile = project.objects.fileProperty()

    @OutputFile
    final RegularFileProperty wavFile = project.objects.fileProperty()

    @TaskAction
    void dump() {
        def voiceDataDumper = new VoiceDataDumper()
        def audioTimelineFileName = waveTimelineFile.get().asFile.path
        def basenameTimelineFileName = basenameTimelineFile.get().asFile.path
        def unitFileName = halfPhoneUnitFile.get().asFile.path
        def featureFileName = acousticFeatureFile.get().asFile.path
        def textGridFilename = textGridFile.get().asFile.path
        def wavFilename = wavFile.get().asFile.path
        voiceDataDumper.loadUnitDatabase(audioTimelineFileName, basenameTimelineFileName, unitFileName)
        voiceDataDumper.loadFeatureFile(featureFileName)
        println "All files loaded."
        voiceDataDumper.dumpTextGrid(textGridFilename)
        println "Dumped TextGrid to $textGridFilename"
        voiceDataDumper.dumpAudio(wavFilename)
        println "Dumped audio to $wavFilename"
    }
}
