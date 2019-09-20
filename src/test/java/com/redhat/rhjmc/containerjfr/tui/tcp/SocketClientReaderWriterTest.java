package com.redhat.rhjmc.containerjfr.tui.tcp;

import com.redhat.rhjmc.containerjfr.core.log.Logger;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.util.Scanner;
import java.util.concurrent.Semaphore;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SocketClientReaderWriterTest {
    @Mock
    Logger logger;

    @Mock
    Semaphore semaphore;

    @Mock
    Socket s;

    @Mock
    Scanner scanner;

    @Mock
    OutputStreamWriter writer;

    SocketClientReaderWriter scrw;

    @BeforeEach
    void setup() throws Exception {
        scrw = new SocketClientReaderWriter(logger, semaphore, s, scanner, writer);
    }

    @Test
    void shouldClose() throws Exception {
        scrw.close();

        verify(scanner).close();
        verify(writer).close();
        verify(s).close();
    }

    @Test
    void shouldReadLine() throws Exception {
        String result = "read line result";
        when(scanner.nextLine()).thenReturn(result);

        reset(semaphore);

        MatcherAssert.assertThat(scrw.readLine(), Matchers.equalTo(result));

        verify(semaphore).acquire();
        verify(semaphore).release();
    }

    @Test
    void shouldReadLineWarnOnException() throws Exception {
        InterruptedException e = new InterruptedException();
        doThrow(e).when(semaphore).acquire();

        scrw.readLine();

        verify(logger).warn(e);
    }

    @Test
    void shouldReadLineReleaseOnException() throws Exception {
        RuntimeException e = new RuntimeException();
        when(scanner.nextLine()).thenThrow(e);

        reset(semaphore);
        Assertions.assertThrows(RuntimeException.class, scrw::readLine);

        verify(semaphore).acquire();
        verify(semaphore).release();
    }

    @Test
    void shouldPrint() throws Exception {
        String content = "printed content";

        scrw.print(content);

        verify(writer).write(content);
        verify(writer).flush();
    }

    @Test
    void shouldPrintWarnOnException() throws Exception {
        IOException e = new IOException();
        doThrow(e).when(writer).flush();

        scrw.print("some content");

        verify(logger).warn(e);
    }

    @Test
    void shouldPrintReleaseOnException() throws Exception {
        IOException e = new IOException();
        doThrow(e).when(writer).flush();

        reset(semaphore);
        scrw.print("some content");

        verify(semaphore).acquire();
        verify(semaphore).release();
    }
}
