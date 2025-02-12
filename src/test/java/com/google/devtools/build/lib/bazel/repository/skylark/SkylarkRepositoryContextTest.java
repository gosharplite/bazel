// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.bazel.repository.skylark;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.CharStreams;
import com.google.devtools.build.lib.bazel.repository.downloader.HttpDownloader;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.RuleClass.Builder.RuleClassType;
import com.google.devtools.build.lib.packages.Type;
import com.google.devtools.build.lib.packages.WorkspaceFactoryHelper;
import com.google.devtools.build.lib.pkgcache.PathPackageLocator;
import com.google.devtools.build.lib.rules.repository.RepositoryFunction.RepositoryFunctionException;
import com.google.devtools.build.lib.skyframe.BazelSkyframeExecutorConstants;
import com.google.devtools.build.lib.syntax.BuiltinFunction;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.Expression;
import com.google.devtools.build.lib.syntax.FuncallExpression;
import com.google.devtools.build.lib.syntax.FunctionSignature;
import com.google.devtools.build.lib.syntax.ParserInput;
import com.google.devtools.build.lib.syntax.StarlarkSemantics;
import com.google.devtools.build.lib.syntax.StarlarkThread;
import com.google.devtools.build.lib.testutil.Scratch;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.skyframe.SkyFunction;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

/**
 * Unit tests for complex function of SkylarkRepositoryContext.
 */
@RunWith(JUnit4.class)
public class SkylarkRepositoryContextTest {

  private Scratch scratch;
  private Path outputDirectory;
  private Root root;
  private Path workspaceFile;
  private SkylarkRepositoryContext context;

  private static String ONE_LINE_PATCH = "@@ -1,1 +1,2 @@\n line one\n+line two\n";

  @Before
  public void setUp() throws Exception {
    scratch = new Scratch("/");
    outputDirectory = scratch.dir("/outputDir");
    root = Root.fromPath(scratch.dir("/wsRoot"));
    workspaceFile = scratch.file("/wsRoot/WORKSPACE");
  }

  protected static RuleClass buildRuleClass(Attribute... attributes) {
    RuleClass.Builder ruleClassBuilder =
        new RuleClass.Builder("test", RuleClassType.WORKSPACE, true);
    for (Attribute attr : attributes) {
      ruleClassBuilder.addOrOverrideAttribute(attr);
    }
    ruleClassBuilder.setWorkspaceOnly();
    ruleClassBuilder.setConfiguredTargetFunction(
        new BuiltinFunction("test", FunctionSignature.ANY) {
          public void invoke(
              List<Object> args, Map<String, Object> kwargs, StarlarkThread thread) {}
        });
    return ruleClassBuilder.build();
  }

  protected void setUpContextForRule(
      Map<String, Object> kwargs,
      ImmutableSet<PathFragment> ignoredPathFragments,
      Attribute... attributes)
      throws Exception {
    Package.Builder packageBuilder =
        Package.newExternalPackageBuilder(
            Package.Builder.DefaultHelper.INSTANCE,
            RootedPath.toRootedPath(root, workspaceFile),
            "runfiles",
            StarlarkSemantics.DEFAULT_SEMANTICS);
    ExtendedEventHandler listener = Mockito.mock(ExtendedEventHandler.class);
    ParserInput input = ParserInput.fromLines("test()");
    FuncallExpression ast = (FuncallExpression) Expression.parse(input);
    Rule rule =
        WorkspaceFactoryHelper.createAndAddRepositoryRule(
            packageBuilder, buildRuleClass(attributes), null, kwargs, ast.getLocation());
    HttpDownloader downloader = Mockito.mock(HttpDownloader.class);
    SkyFunction.Environment environment = Mockito.mock(SkyFunction.Environment.class);
    Mockito.when(environment.getListener()).thenReturn(listener);
    PathPackageLocator packageLocator =
        new PathPackageLocator(
            outputDirectory,
            ImmutableList.of(root),
            BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY);
    context =
        new SkylarkRepositoryContext(
            rule,
            packageLocator,
            outputDirectory,
            ignoredPathFragments,
            environment,
            ImmutableMap.of("FOO", "BAR"),
            downloader,
            null,
            1.0,
            new HashMap<>(),
            true);
  }

  protected void setUpContexForRule(String name) throws Exception {
    setUpContextForRule(ImmutableMap.of("name", name), ImmutableSet.of());
  }

  @Test
  public void testAttr() throws Exception {
    setUpContextForRule(
        ImmutableMap.of("name", "test", "foo", "bar"),
        ImmutableSet.of(),
        Attribute.attr("foo", Type.STRING).build());

    assertThat(context.getAttr().getFieldNames()).contains("foo");
    assertThat(context.getAttr().getValue("foo")).isEqualTo("bar");
  }

  @Test
  public void testWhich() throws Exception {
    setUpContexForRule("test");
    SkylarkRepositoryContext.setPathEnvironment("/bin", "/path/sbin", ".");
    scratch.file("/bin/true").setExecutable(true);
    scratch.file("/path/sbin/true").setExecutable(true);
    scratch.file("/path/sbin/false").setExecutable(true);
    scratch.file("/path/bin/undef").setExecutable(true);
    scratch.file("/path/bin/def").setExecutable(true);
    scratch.file("/bin/undef");

    assertThat(context.which("anything", null)).isNull();
    assertThat(context.which("def", null)).isNull();
    assertThat(context.which("undef", null)).isNull();
    assertThat(context.which("true", null).toString()).isEqualTo("/bin/true");
    assertThat(context.which("false", null).toString()).isEqualTo("/path/sbin/false");
  }

  @Test
  public void testFile() throws Exception {
    setUpContexForRule("test");
    context.createFile(context.path("foobar"), "", true, true, null);
    context.createFile(context.path("foo/bar"), "foobar", true, true, null);
    context.createFile(context.path("bar/foo/bar"), "", true, true, null);

    testOutputFile(outputDirectory.getChild("foobar"), "");
    testOutputFile(outputDirectory.getRelative("foo/bar"), "foobar");
    testOutputFile(outputDirectory.getRelative("bar/foo/bar"), "");

    try {
      context.createFile(context.path("/absolute"), "", true, true, null);
      fail("Expected error on creating path outside of the repository directory");
    } catch (RepositoryFunctionException ex) {
      assertThat(ex)
          .hasCauseThat()
          .hasMessageThat()
          .isEqualTo("Cannot write outside of the repository directory for path /absolute");
    }
    try {
      context.createFile(context.path("../somepath"), "", true, true, null);
      fail("Expected error on creating path outside of the repository directory");
    } catch (RepositoryFunctionException ex) {
      assertThat(ex)
          .hasCauseThat()
          .hasMessageThat()
          .isEqualTo("Cannot write outside of the repository directory for path /somepath");
    }
    try {
      context.createFile(context.path("foo/../../somepath"), "", true, true, null);
      fail("Expected error on creating path outside of the repository directory");
    } catch (RepositoryFunctionException ex) {
      assertThat(ex)
          .hasCauseThat()
          .hasMessageThat()
          .isEqualTo("Cannot write outside of the repository directory for path /somepath");
    }
  }

  @Test
  public void testDelete() throws Exception {
    setUpContexForRule("testDelete");
    Path bar = outputDirectory.getRelative("foo/bar");
    SkylarkPath barPath = context.path(bar.getPathString());
    context.createFile(barPath, "content", true, true, null);
    assertThat(context.delete(barPath, null)).isTrue();

    assertThat(context.delete(barPath, null)).isFalse();

    Path tempFile = scratch.file("/abcde/b", "123");
    assertThat(context.delete(context.path(tempFile.getPathString()), null)).isTrue();

    Path innerDir = scratch.dir("/some/inner");
    scratch.dir("/some/inner/deeper");
    scratch.file("/some/inner/deeper.txt");
    scratch.file("/some/inner/deeper/1.txt");
    assertThat(context.delete(innerDir.toString(), null)).isTrue();

    Path underWorkspace = root.getRelative("under_workspace");
    try {
      context.delete(underWorkspace.toString(), null);
      fail();
    } catch (EvalException expected) {
      assertThat(expected.getMessage())
          .startsWith("delete() can only be applied to external paths");
    }

    scratch.file(underWorkspace.getPathString(), "123");
    setUpContextForRule(
        ImmutableMap.of("name", "test"), ImmutableSet.of(PathFragment.create("under_workspace")));
    assertThat(context.delete(underWorkspace.toString(), null)).isTrue();
  }

  @Test
  public void testRead() throws Exception {
    setUpContexForRule("test");
    context.createFile(context.path("foo/bar"), "foobar", true, true, null);

    String content = context.readFile(context.path("foo/bar"), null);
    assertThat(content).isEqualTo("foobar");
  }

  @Test
  public void testPatch() throws Exception {
    setUpContexForRule("test");
    SkylarkPath foo = context.path("foo");
    context.createFile(foo, "line one\n", false, true, null);
    SkylarkPath patchFile = context.path("my.patch");
    context.createFile(
        context.path("my.patch"), "--- foo\n+++ foo\n" + ONE_LINE_PATCH, false, true, null);
    context.patch(patchFile, 0, null);
    testOutputFile(foo.getPath(), String.format("line one%nline two%n"));
  }

  @Test
  public void testCannotFindFileToPatch() throws Exception {
    setUpContexForRule("test");
    SkylarkPath patchFile = context.path("my.patch");
    context.createFile(
        context.path("my.patch"), "--- foo\n+++ foo\n" + ONE_LINE_PATCH, false, true, null);
    try {
      context.patch(patchFile, 0, null);
      fail("Expected RepositoryFunctionException");
    } catch (RepositoryFunctionException ex) {
      assertThat(ex)
          .hasCauseThat()
          .hasMessageThat()
          .isEqualTo(
              "Error applying patch /outputDir/my.patch: Cannot find file to patch (near line 1)"
                  + ", old file name (foo) doesn't exist, new file name (foo) doesn't exist.");
    }
  }

  @Test
  public void testPatchOutsideOfExternalRepository() throws Exception {
    setUpContexForRule("test");
    SkylarkPath patchFile = context.path("my.patch");
    context.createFile(
        context.path("my.patch"),
        "--- ../other_root/foo\n" + "+++ ../other_root/foo\n" + ONE_LINE_PATCH,
        false,
        true,
        null);
    try {
      context.patch(patchFile, 0, null);
      fail("Expected RepositoryFunctionException");
    } catch (RepositoryFunctionException ex) {
      assertThat(ex)
          .hasCauseThat()
          .hasMessageThat()
          .isEqualTo(
              "Error applying patch /outputDir/my.patch: Cannot patch file outside of external "
                  + "repository (/outputDir), file path = \"../other_root/foo\" at line 1");
    }
  }

  @Test
  public void testPatchErrorWasThrown() throws Exception {
    setUpContexForRule("test");
    SkylarkPath foo = context.path("foo");
    SkylarkPath patchFile = context.path("my.patch");
    context.createFile(foo, "line three\n", false, true, null);
    context.createFile(
        context.path("my.patch"), "--- foo\n+++ foo\n" + ONE_LINE_PATCH, false, true, null);
    try {
      context.patch(patchFile, 0, null);
      fail("Expected RepositoryFunctionException");
    } catch (RepositoryFunctionException ex) {
      assertThat(ex)
          .hasCauseThat()
          .hasMessageThat()
          .isEqualTo(
              "Error applying patch /outputDir/my.patch: Incorrect Chunk: the chunk content "
                  + "doesn't match the target\n"
                  + "**Original Position**: 1\n"
                  + "\n"
                  + "**Original Content**:\n"
                  + "line one\n"
                  + "\n"
                  + "**Revised Content**:\n"
                  + "line one\n"
                  + "line two\n");
    }
  }

  @Test
  public void testSymlink() throws Exception {
    setUpContexForRule("test");
    context.createFile(context.path("foo"), "foobar", true, true, null);

    context.symlink(context.path("foo"), context.path("bar"), null);
    testOutputFile(outputDirectory.getChild("bar"), "foobar");

    assertThat(context.path("bar").realpath()).isEqualTo(context.path("foo"));
  }

  private void testOutputFile(Path path, String content) throws IOException {
    assertThat(path.exists()).isTrue();
    try (InputStreamReader reader =
        new InputStreamReader(path.getInputStream(), StandardCharsets.UTF_8)) {
      assertThat(CharStreams.toString(reader)).isEqualTo(content);
    }
  }

  @Test
  public void testDirectoryListing() throws Exception {
    setUpContexForRule("test");
    scratch.file("/my/folder/a");
    scratch.file("/my/folder/b");
    scratch.file("/my/folder/c");
    assertThat(context.path("/my/folder").readdir()).containsExactly(
        context.path("/my/folder/a"), context.path("/my/folder/b"), context.path("/my/folder/c"));
  }
}
