package org.praat.io.text

class ChronoTextGrid {

    def xmin
    def xmax
    def numberOfTiers
    def tiers = []

    public read(String path) {
        read(new File(path))
    }

    public read(File file) {
        file.withReader('UTF-8') { input ->
            assert input.readLine() == '"Praat chronological TextGrid text file"'
            (xmin, xmax) = input.readLine().split().take(2).collect { it as double }
            numberOfTiers = input.readLine().split().first() as int
            (1..numberOfTiers).each {
                def fields = input.readLine().split()
                def (tierClass, name) = fields.take(2).collect { it.replaceAll('"', '') }
                def (xmin, xmax) = fields.drop(2).collect { it as double }
                tiers << [class: tierClass, name: name, xmin: xmin, xmax: xmax, intervals: []]
            }
            def t
            def start
            def end
            input.eachLine { line ->
                if (line) {
                    switch (line[0]) {
                        case ~/\d/:
                            def fields = line.split()
                            t = fields.first() as int
                            (start, end) = fields.drop(1).collect { it as double }
                            break
                        case '"':
                            def text = line.replaceAll('"', '')
                            tiers[t - 1].intervals << [start: start, end: end, text: text]
                            break
                    }
                }
            }
        }
        return this
    }
}
