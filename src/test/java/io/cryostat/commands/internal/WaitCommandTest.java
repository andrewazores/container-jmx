/*-
 * #%L
 * Cryostat
 * %%
 * Copyright (C) 2020 - 2021 The Cryostat Authors
 * %%
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or data
 * (collectively the "Software"), free of charge and under any and all copyright
 * rights in the Software, and any and all patent rights owned or freely
 * licensable by each licensor hereunder covering either (i) the unmodified
 * Software as contributed to or provided by such licensor, or (ii) the Larger
 * Works (as defined below), to deal in both
 *
 * (a) the Software, and
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software (each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 * The above copyright notice and either this complete permission notice or at
 * a minimum a reference to the UPL must be included in all copies or
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 * #L%
 */
package io.cryostat.commands.internal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.TimeUnit;

import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import io.cryostat.TestBase;
import io.cryostat.core.sys.Clock;

@ExtendWith(MockitoExtension.class)
class WaitCommandTest extends TestBase {

    WaitCommand command;
    @Mock Clock clock;

    @BeforeEach
    void setup() {
        command = new WaitCommand(mockClientWriter, clock);
    }

    @Test
    void shouldBeNamedWait() {
        MatcherAssert.assertThat(command.getName(), Matchers.equalTo("wait"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void shouldNotValidateIncorrectArgc(int argc) {
        Exception e =
                assertThrows(
                        FailedValidationException.class, () -> command.validate(new String[argc]));
        String errorMessage = "Expected one argument";
        MatcherAssert.assertThat(stdout(), Matchers.equalTo(errorMessage + '\n'));
        MatcherAssert.assertThat(e.getMessage(), Matchers.equalTo(errorMessage));
    }

    @Test
    void shouldNotValidateNullArg() {
        Exception e =
                assertThrows(
                        FailedValidationException.class,
                        () -> command.validate(new String[] {null}));
        String errorMessage = "One or more arguments were null";
        MatcherAssert.assertThat(stdout(), Matchers.equalTo(errorMessage + '\n'));
        MatcherAssert.assertThat(e.getMessage(), Matchers.equalTo(errorMessage));
    }

    @Test
    void shouldExpectIntegerFormattedArg() {
        Exception e =
                assertThrows(
                        FailedValidationException.class,
                        () -> command.validate(new String[] {"f"}));
        String errorMessage = "f is an invalid integer";
        MatcherAssert.assertThat(stdout(), Matchers.equalTo(errorMessage + '\n'));
        MatcherAssert.assertThat(e.getMessage(), Matchers.equalTo(errorMessage));
    }

    @Test
    void shouldValidateArgs() {
        assertDoesNotThrow(() -> command.validate(new String[] {"10"}));
        MatcherAssert.assertThat(stdout(), Matchers.emptyString());
    }

    @Test
    void shouldBeAvailable() {
        assertTrue(command.isAvailable());
    }

    @Test
    void testExecution() throws Exception {
        when(clock.getWallTime()).thenReturn(0L).thenReturn(1_000L).thenReturn(2_000L);
        command.execute(new String[] {"1"});
        MatcherAssert.assertThat(stdout(), Matchers.equalTo(". \n"));
        verify(clock, Mockito.times(2)).getWallTime();
        verify(clock).sleep(TimeUnit.SECONDS, 1);
    }
}