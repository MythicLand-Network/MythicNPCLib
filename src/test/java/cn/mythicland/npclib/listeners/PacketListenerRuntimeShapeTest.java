package cn.mythicland.npclib.listeners;

import static org.junit.Assert.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

public class PacketListenerRuntimeShapeTest {
    @Test
    public void packetListener_shouldNotUseNestedRunnableForAsyncCallback() throws Exception {
        String source = Files.readString(
                Path.of("src", "main", "java", "cn", "mythicland", "npclib", "listeners", "PacketListener.java"),
                StandardCharsets.UTF_8
        );

        assertEquals(0, this.count(source, "TaskCallNpcInteractEvent"));
    }

    private int count(String source, String pattern) {
        int count = 0;
        int index = 0;
        while ((index = source.indexOf(pattern, index)) >= 0) {
            count++;
            index += pattern.length();
        }
        return count;
    }
}
