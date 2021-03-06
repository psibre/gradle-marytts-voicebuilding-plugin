package de.dfki.mary.voicebuilding.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

class GenerateVoiceConfig extends DefaultTask {

    @Input
    final MapProperty<String, Object> config = project.objects.mapProperty(String, Object)

    @OutputFile
    final RegularFileProperty destFile = project.objects.fileProperty()

    GenerateVoiceConfig() {
        this.config.set([:])
    }

    @TaskAction
    void generate() {
        destFile.get().asFile.withWriter 'UTF-8', { writer ->
            writer.println """|# Auto-generated config file for voice ${project.marytts.voice.name}
                              |
                              |name = ${project.marytts.voice.name}
                              |locale = ${project.marytts.voice.maryLocale}
                              |
                              |${voiceType()}.voices.list = ${project.marytts.voice.name}
                              |
                              |""".stripMargin()
            ([
                    domain      : 'general',
                    gender      : project.marytts.voice.gender,
                    locale      : project.marytts.voice.locale,
                    samplingRate: project.marytts.voice.samplingRate
            ] + config.get()).each { key, value ->
                writer.println "voice.${project.marytts.voice.name}.$key = $value"
            }
        }
    }

    String voiceType() {
        def type
        switch (project.marytts.voice.type) {
            case ~/hs?mm/:
                type = 'hmm'
                break
            default:
                type = 'unitselection'
        }
        return type
    }
}
