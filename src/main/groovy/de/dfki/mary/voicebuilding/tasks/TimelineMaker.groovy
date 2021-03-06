package de.dfki.mary.voicebuilding.tasks

import groovy.json.JsonSlurper
import marytts.tools.voiceimport.TimelineWriter
import marytts.util.data.Datagram
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*

class TimelineMaker extends DefaultTask {

    @Input
    final Property<Integer> sampleRate = project.objects.property(Integer)

    @Input
    final Property<BigDecimal> idxIntervalInSeconds = project.objects.property(BigDecimal)

    @InputFile
    final RegularFileProperty basenamesFile = project.objects.fileProperty()

    @InputDirectory
    final DirectoryProperty srcDir = project.objects.directoryProperty()

    @OutputFile
    final RegularFileProperty destFile = project.objects.fileProperty()

    @TaskAction
    void make() {
        def timeline = new TimelineWriter(destFile.get().asFile.path, '\n', sampleRate.get(), idxIntervalInSeconds.get());
        basenamesFile.get().asFile.eachLine('UTF-8') { basename ->
            def datagramFile = srcDir.file("${basename}.json").get().asFile
            def json = new JsonSlurper().parse(datagramFile)
            json.each { jsonDatagram ->
                def datagram = new Datagram(jsonDatagram.duration, jsonDatagram.data.decodeBase64())
                timeline.feed(datagram, sampleRate.get())
            }
        }
        timeline.close()
    }
}
