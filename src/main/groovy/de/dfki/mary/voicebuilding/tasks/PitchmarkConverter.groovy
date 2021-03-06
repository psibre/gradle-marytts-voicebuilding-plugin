package de.dfki.mary.voicebuilding.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

class PitchmarkConverter extends DefaultTask {

    @InputFile
    final RegularFileProperty basenamesFile = project.objects.fileProperty()

    @InputDirectory
    final DirectoryProperty srcDir = project.objects.directoryProperty()

    @OutputDirectory
    final DirectoryProperty destDir = project.objects.directoryProperty()

    @TaskAction
    void convert() {
        basenamesFile.get().asFile.eachLine('UTF-8') { basename ->
            def srcFile = srcDir.file("${basename}.PointProcess").get().asFile
            def destFile = destDir.file("${basename}.pm").get().asFile
            // ignore Praat header...
            def times = srcFile.readLines().drop(5)
            // ...up to line 6, which tells number of points (the remaining lines are the times)
            def nx = times.pop()
            destFile.withWriter { pm ->
                pm.println 'EST_File Track'
                pm.println 'DataType ascii'
                pm.println "NumFrames $nx"
                pm.println 'NumChannels 0'
                pm.println 'NumAuxChannels 0'
                pm.println 'EqualSpace 0'
                pm.println 'BreaksPresent true'
                pm.println 'CommentChar ;'
                pm.println 'EST_Header_End'
                pm.println times.collect { "${it as float}\t1" }.join('\n')
            }
        }
    }
}
