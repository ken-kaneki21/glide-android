package com.gharaudio.launcher

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

internal const val SPEECH_MODEL_SHA = "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498"
internal const val SPEECH_MODEL_BYTES = 41205931L
internal const val SPEECH_MODEL_ROOT = "vosk-model-small-en-us-0.15"
internal fun speechFileHash(file: File, checkpoint: () -> Unit = {}): String {
    val digest=MessageDigest.getInstance("SHA-256")
    file.inputStream().use { stream ->
        val bytes=ByteArray(65536)
        while(true){checkpoint();val n=stream.read(bytes);if(n<0)break;digest.update(bytes,0,n)}
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
}

/** Only the pinned official model archive is accepted. No native code is imported. */
internal fun importSpeechModel(input: InputStream, destination: File, checkpoint: () -> Unit = {}) {
    val parent=checkNotNull(destination.parentFile)
    check(parent.isDirectory || parent.mkdirs()) { "Cannot create local model storage" }
    val stage=File(parent,"speech-import-"+java.util.UUID.randomUUID())
    check(stage.mkdir())
    try {
        val archive=File(stage,"input.zip")
        archive.outputStream().use { output ->
            val bytes=ByteArray(65536);var total=0L
            while(true){
                checkpoint();val n=input.read(bytes);if(n<0)break
                total+=n;require(total<=SPEECH_MODEL_BYTES){"Wrong model archive size"};output.write(bytes,0,n)
            }
            require(total==SPEECH_MODEL_BYTES){"Incomplete model archive"}
        }
        require(speechFileHash(archive,checkpoint)==SPEECH_MODEL_SHA){"Wrong or damaged model archive"}
        val unpacked=File(stage,"unpacked");check(unpacked.mkdir())
        var total=0L;var entries=0
        val receipts=mutableListOf<String>()
        ZipFile(archive).use { zip ->
            val iterator=zip.entries()
            while(iterator.hasMoreElements()) {
                checkpoint();val entry=iterator.nextElement();entries++
                require(entries<=64 && entry.name.startsWith("$SPEECH_MODEL_ROOT/") &&
                    !entry.name.contains('\\') && !entry.name.contains(':') &&
                    entry.name.split('/').none{it==".." || it=="."}) { "Unsafe model archive" }
                val file=File(unpacked,entry.name)
                require(file.canonicalPath.startsWith(unpacked.canonicalPath+File.separator))
                if(entry.isDirectory){check(file.isDirectory || file.mkdirs());continue}
                check(file.parentFile?.isDirectory == true || file.parentFile?.mkdirs() == true)
                check(!file.exists()) { "Duplicate model entry" }
                zip.getInputStream(entry).use { source -> file.outputStream().use { target ->
                    val bytes=ByteArray(65536)
                    while(true){checkpoint();val n=source.read(bytes);if(n<0)break;total+=n
                        require(total<=100L*1024*1024){"Expanded model too large"};target.write(bytes,0,n)}
                } }
                receipts.add(speechFileHash(file,checkpoint)+" "+entry.name.removePrefix("$SPEECH_MODEL_ROOT/"))
            }
        }
        val model=File(unpacked,SPEECH_MODEL_ROOT)
        require(File(model,"am/final.mdl").isFile && File(model,"conf/model.conf").isFile) { "Incomplete model" }
        File(model,"verified-files.txt").writeText(receipts.joinToString("\n"))
        checkpoint()
        // Recovery material must survive staging cleanup if restoration itself fails.
        val previous=File(parent,"speech-previous-"+java.util.UUID.randomUUID())
        if(destination.exists())check(destination.renameTo(previous)){"Cannot preserve the current model"}
        if(!model.renameTo(destination)){
            if(previous.exists())check(previous.renameTo(destination)){"Model recovery needs attention"}
            error("Cannot finalize imported model")
        }
        if(previous.exists())previous.deleteRecursively()
    } finally { stage.deleteRecursively() } // Only this operation's unique staging directory.
}

internal fun validateSpeechModel(model: File, checkpoint: () -> Unit = {}) {
    val receipt=File(model,"verified-files.txt")
    require(receipt.isFile && receipt.length()<=16384) { "Import the verified English model first" }
    val lines=receipt.readLines();require(lines.size in 1..64)
    for(line in lines){
        checkpoint();val parts=line.split(' ',limit=2);require(parts.size==2 && parts[0].length==64)
        val file=File(model,parts[1])
        require(file.canonicalPath.startsWith(model.canonicalPath+File.separator) && file.isFile)
        require(speechFileHash(file,checkpoint)==parts[0]) { "Local model is damaged" }
    }
    require(File(model,"am/final.mdl").isFile && File(model,"conf/model.conf").isFile)
}
