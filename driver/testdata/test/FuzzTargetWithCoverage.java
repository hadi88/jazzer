// Copyright 2021 Code Intelligence GmbH
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package test;

import com.code_intelligence.jazzer.runtime.CoverageMap;

public class FuzzTargetWithCoverage {
  public static boolean fuzzerTestOneInput(byte[] input) {
    // manually increase the first coverage counter
    byte counter = CoverageMap.mem.get(0);
    counter++;
    if (counter == 0)
      counter--;
    CoverageMap.mem.put(0, counter);
    return false;
  }
}
