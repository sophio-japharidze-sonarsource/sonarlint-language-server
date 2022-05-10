/*
 * SonarLint Language Server
 * Copyright (C) 2009-2022 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonarsource.sonarlint.ls.mediumtests;

import java.nio.file.Path;
import java.util.Map;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.sonar.scanner.protocol.Constants.Severity;
import org.sonar.scanner.protocol.input.ScannerInput;
import org.sonarsource.sonarlint.shaded.org.sonarqube.ws.Components;
import org.sonarsource.sonarlint.shaded.org.sonarqube.ws.ProjectBranches;
import org.sonarsource.sonarlint.shaded.org.sonarqube.ws.ProjectBranches.Branch;
import org.sonarsource.sonarlint.shaded.org.sonarqube.ws.Qualityprofiles;
import org.sonarsource.sonarlint.shaded.org.sonarqube.ws.Qualityprofiles.SearchWsResponse.QualityProfile;
import org.sonarsource.sonarlint.shaded.org.sonarqube.ws.Rules;
import org.sonarsource.sonarlint.shaded.org.sonarqube.ws.Rules.Active;
import org.sonarsource.sonarlint.shaded.org.sonarqube.ws.Rules.ActiveList;
import org.sonarsource.sonarlint.shaded.org.sonarqube.ws.Rules.Actives;
import org.sonarsource.sonarlint.shaded.org.sonarqube.ws.Rules.Actives.Builder;
import org.sonarsource.sonarlint.shaded.org.sonarqube.ws.Rules.Rule;
import org.sonarsource.sonarlint.shaded.org.sonarqube.ws.Settings;
import testutils.MockWebServerExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

class ConnectedModeMediumTests extends AbstractLanguageServerMediumTests {

  private static final String QPROFILE_KEY = "AXDEr5Q7LjElHiH99ZhW";
  private static final String JAVASCRIPT_S1481 = "javascript:S1481";

  @RegisterExtension
  private final MockWebServerExtension mockWebServerExtension = new MockWebServerExtension();

  private static final String CONNECTION_ID = "mediumTests";
  @TempDir
  public static Path folder1BaseDir;

  @BeforeAll
  public static void initialize() throws Exception {

    initialize(Map.of(
      "telemetryStorage", "not/exists",
      "productName", "SLCORE tests",
      "productVersion", "0.1"), new WorkspaceFolder(folder1BaseDir.toUri().toString(), "My Folder 1"));
  }

  @BeforeEach
  public void mockSonarQube() {
    mockWebServerExtension.addStringResponse("/api/system/status", "{\"status\": \"UP\", \"version\": \"9.3\", \"id\": \"xzy\"}");
    mockWebServerExtension.addProtobufResponse("/api/components/search.protobuf?qualifiers=TRK&ps=500&p=1", Components.SearchWsResponse.newBuilder().build());
    mockWebServerExtension.addProtobufResponse("/api/components/tree.protobuf?qualifiers=FIL,UTS&component=myProject&ps=500&p=1", Components.TreeWsResponse.newBuilder().build());
    mockWebServerExtension.addStringResponse("/api/plugins/installed",
      "{\"plugins\":[{\"key\": \"javascript\", \"hash\": \"not_used\", \"filename\": \"not_used\", \"sonarLintSupported\": true}]}");
    mockWebServerExtension.addProtobufResponse("/api/settings/values.protobuf?component=myProject", Settings.Values.newBuilder().build());
    mockWebServerExtension.addProtobufResponse("/api/qualityprofiles/search.protobuf?project=myProject", Qualityprofiles.SearchWsResponse.newBuilder()
      .addProfiles(QualityProfile.newBuilder()
        .setKey(QPROFILE_KEY)
        .setLanguage("js")
        .setRulesUpdatedAt("2022-03-14T11:13:26+0000")
        .build())
      .build());
    Builder activeBuilder = Actives.newBuilder();
    activeBuilder.putActives(JAVASCRIPT_S1481, ActiveList.newBuilder().addActiveList(Active.newBuilder().setSeverity("BLOCKER")).build());
    mockWebServerExtension.addProtobufResponse(
      "/api/rules/search.protobuf?qprofile=" + QPROFILE_KEY + "&activation=true&f=templateKey,actives&types=CODE_SMELL,BUG,VULNERABILITY&ps=500&p=1",
      Rules.SearchResponse.newBuilder()
        .setActives(activeBuilder.build())
        .addRules(Rule.newBuilder()
          .setKey(JAVASCRIPT_S1481)
          .setLang("js")
          .build())
        .build());
    mockWebServerExtension.addProtobufResponse(
      "/api/project_branches/list.protobuf?project=myProject",
      ProjectBranches.ListWsResponse.newBuilder()
        .addBranches(Branch.newBuilder()
          .setName("master")
          .setIsMain(true)
          .build())
        .build());
  }

  @Override
  protected void setUpFolderSettings(Map<String, Map<String, Object>> folderSettings) {
    setShowVerboseLogs(client.globalSettings, true);
    setShowAnalyzerLogs(client.globalSettings, true);
    addSonarQubeConnection(client.globalSettings, CONNECTION_ID, mockWebServerExtension.url("/"), "xxxxx");
    bindProject(getFolderSettings(folder1BaseDir.toUri().toString()), CONNECTION_ID, "myProject");
  }

  @Test
  void analysisConnected_no_server_issues() throws Exception {
    var uriInFolder = folder1BaseDir.resolve("inFolder.js").toUri().toString();
    didOpen(uriInFolder, "javascript", "function foo() {\n  var toto = 0;\n  var plouf = 0;\n}");

    awaitUntilAsserted(() -> assertThat(client.getDiagnostics(uriInFolder))
      .extracting(startLine(), startCharacter(), endLine(), endCharacter(), code(), Diagnostic::getSource, Diagnostic::getMessage, Diagnostic::getSeverity)
      .containsExactlyInAnyOrder(
        tuple(1, 6, 1, 10, JAVASCRIPT_S1481, "sonarlint", "Remove the declaration of the unused 'toto' variable.", DiagnosticSeverity.Warning),
        tuple(2, 6, 2, 11, JAVASCRIPT_S1481, "sonarlint", "Remove the declaration of the unused 'plouf' variable.", DiagnosticSeverity.Warning)));
  }

  @Disabled("Issue tracking broken")
  @Test
  void analysisConnected_track_server_issues() throws Exception {
    mockWebServerExtension.addProtobufResponseDelimited(
      "/batch/issues?key=myProject%3AinFolder.js",
      ScannerInput.ServerIssue.newBuilder()
        .setKey("xyz")
        .setRuleRepository("javascript")
        .setRuleKey("S1481")
        .setMsg("Remove the declaration of the unused 'toto' variable.")
        .setSeverity(Severity.INFO)
        .setManualSeverity(true)
        .setPath("inFolder.js")
        .build());

    var uriInFolder = folder1BaseDir.resolve("inFolder.js").toUri().toString();
    didOpen(uriInFolder, "javascript", "function foo() {\n  var toto = 0;\n  var plouf = 0;\n}");

    awaitUntilAsserted(() -> assertThat(client.getDiagnostics(uriInFolder))
      .extracting(startLine(), startCharacter(), endLine(), endCharacter(), code(), Diagnostic::getSource, Diagnostic::getMessage, Diagnostic::getSeverity)
      .containsExactlyInAnyOrder(
        tuple(1, 6, 1, 10, JAVASCRIPT_S1481, "sonarlint", "Remove the declaration of the unused 'toto' variable.", DiagnosticSeverity.Hint),
        tuple(2, 6, 2, 11, JAVASCRIPT_S1481, "sonarlint", "Remove the declaration of the unused 'plouf' variable.", DiagnosticSeverity.Warning)));
  }

}