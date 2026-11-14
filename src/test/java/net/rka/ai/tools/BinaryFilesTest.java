package net.rka.ai.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BinaryFilesTest {

    @Test
    void aTextWriteToABinaryPathIsRefused() {
        assertTrue(BinaryFiles.refusalForPath("gradle/wrapper/gradle-wrapper.jar").isPresent());
        assertTrue(BinaryFiles.refusalForPath("logo.PNG").isPresent());
        assertFalse(BinaryFiles.refusalForPath("src/App.java").isPresent());
        assertFalse(BinaryFiles.refusalForPath("README").isPresent());
    }

    @Test
    void theCommandThatMadeTheFourByteJarIsRefused() {
        String command = "$jar = Join-Path (Get-Location) 'gradle\\wrapper\\gradle-wrapper.jar'; New-Item -ItemType File $jar | Out-Null;"
                + " Add-Content -Path $jar -Value ([byte[]](0x50,0x4B,0x03,0x04)) -Encoding Byte";
        assertTrue(BinaryFiles.refusalForCommand(command).isPresent());
        assertTrue(BinaryFiles.refusalForCommand("echo hi > build/out.zip").isPresent());
    }

    @Test
    void commandsThatMerelyUseBinaryFilesAreNotRefused() {
        assertFalse(BinaryFiles.refusalForCommand("java -jar build/libs/app.jar 2>&1").isPresent());
        assertFalse(BinaryFiles.refusalForCommand(".\\gradlew.bat build --no-daemon").isPresent());
        assertFalse(BinaryFiles.refusalForCommand("Expand-Archive -Path x.zip -DestinationPath out").isPresent());
        assertFalse(BinaryFiles.refusalForCommand("echo hello > notes.txt").isPresent());
    }
}
