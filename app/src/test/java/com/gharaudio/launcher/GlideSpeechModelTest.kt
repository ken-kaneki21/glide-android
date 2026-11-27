package com.gharaudio.launcher

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class GlideSpeechModelTest {
    private fun fixture()=File(checkNotNull(System.getProperty("glideSpeechModelFixture")){"Set glideSpeechModelFixture to the pinned local model ZIP"})
    @Test fun pinnedArchiveImportsValidatesAndDetectsCorruption() {
        val dir=Files.createTempDirectory("glide-speech-test-").toFile()
        try {
            val model=File(dir,"model")
            fixture().inputStream().use{importSpeechModel(it,model)}
            validateSpeechModel(model)
            File(model,"conf/model.conf").appendText("corrupt")
            try{validateSpeechModel(model);fail("Corruption must be rejected")}catch(expected:IllegalArgumentException){}
        } finally {dir.deleteRecursively()}
    }
    @Test fun failedImportPreservesExistingModel() {
        val dir=Files.createTempDirectory("glide-speech-test-").toFile()
        try {
            val model=File(dir,"model");assertTrue(model.mkdir());val marker=File(model,"existing");marker.writeText("keep")
            try{importSpeechModel(ByteArrayInputStream(ByteArray(10)),model);fail("Truncation required")}catch(expected:IllegalArgumentException){}
            assertEquals("keep",marker.readText());assertEquals(listOf("model"),dir.listFiles()!!.map{it.name})
        } finally {dir.deleteRecursively()}
    }
    @Test fun cancelledImportCleansOnlyItsOwnStaging() {
        val dir=Files.createTempDirectory("glide-speech-test-").toFile()
        try {
            val unrelated=File(dir,"unrelated");unrelated.writeText("keep");var calls=0
            try{fixture().inputStream().use{importSpeechModel(it,File(dir,"model")){if(++calls==3)throw CancellationException()}};fail("Cancellation required")}
            catch(expected:CancellationException){}
            assertEquals(listOf("unrelated"),dir.listFiles()!!.map{it.name});assertEquals("keep",unrelated.readText())
        } finally {dir.deleteRecursively()}
    }
    @Test fun missingModelIsRejected() {
        val dir=Files.createTempDirectory("glide-speech-test-").toFile()
        try{try{validateSpeechModel(dir);fail("Missing model required")}catch(expected:IllegalArgumentException){}}
        finally{dir.deleteRecursively()}
    }
}
