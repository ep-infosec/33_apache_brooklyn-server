/*
 * Copyright 2016 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.brooklyn.util.core.internal.winrm;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

import java.util.List;

import org.apache.brooklyn.util.core.internal.winrm.RecordingWinRmTool.ExecParams;

import com.google.common.annotations.Beta;
import com.google.common.base.Predicate;

@Beta
public class ExecCmdAsserts {

    public static void assertExecsContain(List<ExecParams> actuals, List<String> expectedCmds) {
        String errMsg = "actuals="+actuals+"; expected="+expectedCmds;
        assertTrue(actuals.size() >= expectedCmds.size(), "actualSize="+actuals.size()+"; expectedSize="+expectedCmds.size()+"; "+errMsg);
        for (int i = 0; i < expectedCmds.size(); i++) {
            assertExecContains(actuals.get(i), expectedCmds.get(i), errMsg);
        }
    }

    public static void assertExecContains(ExecParams actual, String expectedCmdRegex) {
        assertExecContains(actual, expectedCmdRegex, null);
    }
    
    public static void assertExecContains(ExecParams actual, String expectedCmdRegex, String errMsg) {
        for (String cmd : actual.commands) {
            if (cmd.matches(expectedCmdRegex)) {
                return;
            }
        }
        fail(expectedCmdRegex + " not matched by any commands in " + actual+(errMsg != null ? "; "+errMsg : ""));
    }

    public static void assertExecsNotContains(List<? extends ExecParams> actuals, List<String> expectedNotCmdRegexs) {
        for (ExecParams actual : actuals) {
            assertExecNotContains(actual, expectedNotCmdRegexs);
        }
    }
    
    public static void assertExecNotContains(ExecParams actual, List<String> expectedNotCmdRegexs) {
        for (String cmdRegex : expectedNotCmdRegexs) {
            for (String subActual : actual.commands) {
                if (subActual.matches(cmdRegex)) {
                    fail("Exec should not contain " + cmdRegex + ", but matched by " + actual);
                }
            }
        }
    }

    public static void assertExecsSatisfy(List<ExecParams> actuals, List<? extends Predicate<? super ExecParams>> expectedCmds) {
        String errMsg = "actuals="+actuals+"; expected="+expectedCmds;
        assertTrue(actuals.size() >= expectedCmds.size(), "actualSize="+actuals.size()+"; expectedSize="+expectedCmds.size()+"; "+errMsg);
        for (int i = 0; i < expectedCmds.size(); i++) {
            assertExecSatisfies(actuals.get(i), expectedCmds.get(i), errMsg);
        }
    }

    public static void assertExecSatisfies(ExecParams actual, Predicate<? super ExecParams> expected) {
        assertExecSatisfies(actual, expected, null);
    }
    
    public static void assertExecSatisfies(ExecParams actual, Predicate<? super ExecParams> expected, String errMsg) {
        if (!expected.apply(actual)) {
            fail(expected + " not matched by " + actual + (errMsg != null ? "; "+errMsg : ""));
        }
    }

    public static void assertExecHasNever(List<ExecParams> actuals, String expectedCmd) {
        assertExecHasExactly(actuals, expectedCmd, 0);
    }

    public static void assertExecHasOnlyOnce(List<ExecParams> actuals, String expectedCmd) {
        assertExecHasExactly(actuals, expectedCmd, 1);
    }

    public static void assertExecHasExactly(List<ExecParams> actuals, String expectedCmd, int expectedCount) {
        String errMsg = "actuals="+actuals+"; expected="+expectedCmd;
        int count = 0;
        for (ExecParams actual : actuals) {
            for (String subActual : actual.commands) {
                if (subActual.matches(expectedCmd)) {
                    count++;
                    break;
                }
            }
        }
        assertEquals(count, expectedCount, errMsg);
    }

    public static ExecParams findExecContaining(List<ExecParams> actuals, String cmdRegex) {
        for (ExecParams actual : actuals) {
            for (String subActual : actual.commands) {
                if (subActual.matches(cmdRegex)) {
                    return actual;
                }
            }
        }
        fail("No match for '"+cmdRegex+"' in "+actuals);
        throw new IllegalStateException("unreachable code");
    }
}
