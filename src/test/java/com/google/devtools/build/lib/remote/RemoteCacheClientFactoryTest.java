// Copyright 2019 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.remote;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.testutil.MoreAsserts.assertThrows;

import com.google.devtools.build.lib.clock.JavaClock;
import com.google.devtools.build.lib.remote.common.RemoteCacheClient;
import com.google.devtools.build.lib.remote.disk.CombinedDiskHttpCacheClient;
import com.google.devtools.build.lib.remote.disk.DiskCacheClient;
import com.google.devtools.build.lib.remote.http.HttpCacheClient;
import com.google.devtools.build.lib.remote.options.RemoteOptions;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import com.google.devtools.common.options.Options;
import java.io.IOException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link RemoteCacheClientFactory}. */
@RunWith(JUnit4.class)
public class RemoteCacheClientFactoryTest {

  private final DigestUtil digestUtil = new DigestUtil(DigestHashFunction.SHA256);

  private RemoteOptions remoteOptions;
  private Path workingDirectory;
  private InMemoryFileSystem fs;

  @Before
  public final void setUp() {
    fs = new InMemoryFileSystem(new JavaClock(), DigestHashFunction.SHA256);
    workingDirectory = fs.getPath("/etc/something");
    remoteOptions = Options.getDefaults(RemoteOptions.class);
  }

  @Test
  public void createCombinedCacheWithExistingWorkingDirectory() throws IOException {
    remoteOptions.remoteCache = "http://doesnotexist.com";
    remoteOptions.diskCache = PathFragment.create("/etc/something/cache/here");
    fs.getPath("/etc/something/cache/here").createDirectoryAndParents();

    RemoteCacheClient blobStore =
        RemoteCacheClientFactory.create(
            remoteOptions, /* creds= */ null, workingDirectory, digestUtil);

    assertThat(blobStore).isInstanceOf(CombinedDiskHttpCacheClient.class);
  }

  @Test
  public void createCombinedCacheWithNotExistingWorkingDirectory() throws IOException {
    remoteOptions.remoteCache = "http://doesnotexist.com";
    remoteOptions.diskCache = PathFragment.create("/etc/something/cache/here");
    assertThat(workingDirectory.exists()).isFalse();

    RemoteCacheClient blobStore =
        RemoteCacheClientFactory.create(
            remoteOptions, /* creds= */ null, workingDirectory, digestUtil);

    assertThat(blobStore).isInstanceOf(CombinedDiskHttpCacheClient.class);
    assertThat(workingDirectory.exists()).isTrue();
  }

  @Test
  public void createCombinedCacheWithMissingWorkingDirectoryShouldThrowException() {
    // interesting case: workingDirectory = null -> NPE.
    remoteOptions.remoteCache = "http://doesnotexist.com";
    remoteOptions.diskCache = PathFragment.create("/etc/something/cache/here");

    assertThrows(
        NullPointerException.class,
        () ->
            RemoteCacheClientFactory.create(
                remoteOptions, /* creds= */ null, /* workingDirectory= */ null, digestUtil));
  }

  @Test
  public void createHttpCacheWithProxy() throws IOException {
    remoteOptions.remoteCache = "http://doesnotexist.com";
    remoteOptions.remoteProxy = "unix://some-proxy";

    RemoteCacheClient blobStore =
        RemoteCacheClientFactory.create(
            remoteOptions, /* creds= */ null, workingDirectory, digestUtil);

    assertThat(blobStore).isInstanceOf(HttpCacheClient.class);
  }

  @Test
  public void createHttpCacheFailsWithUnsupportedProxyProtocol() {
    remoteOptions.remoteCache = "http://doesnotexist.com";
    remoteOptions.remoteProxy = "bad-proxy";

    assertThat(
            assertThrows(
                RuntimeException.class,
                () ->
                    RemoteCacheClientFactory.create(
                        remoteOptions, /* creds= */ null, workingDirectory, digestUtil)))
        .hasMessageThat()
        .contains("Remote cache proxy unsupported: bad-proxy");
  }

  @Test
  public void createHttpCacheWithoutProxy() throws IOException {
    remoteOptions.remoteCache = "http://doesnotexist.com";

    RemoteCacheClient blobStore =
        RemoteCacheClientFactory.create(
            remoteOptions, /* creds= */ null, workingDirectory, digestUtil);

    assertThat(blobStore).isInstanceOf(HttpCacheClient.class);
  }

  @Test
  public void createDiskCache() throws IOException {
    remoteOptions.diskCache = PathFragment.create("/etc/something/cache/here");

    RemoteCacheClient blobStore =
        RemoteCacheClientFactory.create(
            remoteOptions, /* creds= */ null, workingDirectory, digestUtil);

    assertThat(blobStore).isInstanceOf(DiskCacheClient.class);
  }

  @Test
  public void isRemoteCacheOptions_httpCacheEnabled() {
    remoteOptions.remoteCache = "http://doesnotexist:90";
    assertThat(RemoteCacheClientFactory.isRemoteCacheOptions(remoteOptions)).isTrue();
  }

  @Test
  public void isRemoteCacheOptions_httpCacheEnabledInUpperCase() {
    remoteOptions.remoteCache = "HTTP://doesnotexist:90";
    assertThat(RemoteCacheClientFactory.isRemoteCacheOptions(remoteOptions)).isTrue();
  }

  @Test
  public void isRemoteCacheOptions_httpsCacheEnabled() {
    remoteOptions.remoteCache = "https://doesnotexist:90";
    assertThat(RemoteCacheClientFactory.isRemoteCacheOptions(remoteOptions)).isTrue();
  }

  @Test
  public void isRemoteCacheOptions_badProtocolStartsWithHttp() {
    remoteOptions.remoteCache = "httplolol://doesnotexist:90";
    assertThat(RemoteCacheClientFactory.isRemoteCacheOptions(remoteOptions)).isFalse();
  }

  @Test
  public void isRemoteCacheOptions_diskCacheEnabled() {
    remoteOptions.diskCache = PathFragment.create("/etc/something/cache/here");
    assertThat(RemoteCacheClientFactory.isRemoteCacheOptions(remoteOptions)).isTrue();
  }

  @Test
  public void isRemoteCacheOptions_httpAndDiskCacheEnabled() {
    remoteOptions.remoteCache = "http://doesnotexist:90";
    remoteOptions.diskCache = PathFragment.create("/etc/something/cache/here");

    assertThat(RemoteCacheClientFactory.isRemoteCacheOptions(remoteOptions)).isTrue();
  }

  @Test
  public void isRemoteCacheOptions_httpsAndDiskCacheEnabled() {
    remoteOptions.remoteCache = "https://doesnotexist:90";
    remoteOptions.diskCache = PathFragment.create("/etc/something/cache/here");

    assertThat(RemoteCacheClientFactory.isRemoteCacheOptions(remoteOptions)).isTrue();
  }

  @Test
  public void isRemoteCacheOptions_httpCacheDisabledWhenGrpcEnabled() {
    remoteOptions.remoteCache = "grpc://doesnotexist:90";

    assertThat(RemoteCacheClientFactory.isRemoteCacheOptions(remoteOptions)).isFalse();
  }

  @Test
  public void isRemoteCacheOptions_httpCacheDisabledWhenNoProtocol() {
    remoteOptions.remoteCache = "doesnotexist:90";

    assertThat(RemoteCacheClientFactory.isRemoteCacheOptions(remoteOptions)).isFalse();
  }

  @Test
  public void isRemoteCacheOptions_diskCacheOptionEmpty() {
    remoteOptions.diskCache = PathFragment.EMPTY_FRAGMENT;
    assertThat(RemoteCacheClientFactory.isRemoteCacheOptions(remoteOptions)).isFalse();
  }

  @Test
  public void isRemoteCacheOptions_remoteHttpCacheOptionEmpty() {
    remoteOptions.remoteCache = "";
    assertThat(RemoteCacheClientFactory.isRemoteCacheOptions(remoteOptions)).isFalse();
  }

  @Test
  public void isRemoteCacheOptions_defaultOptions() {
    assertThat(RemoteCacheClientFactory.isRemoteCacheOptions(remoteOptions)).isFalse();
  }
}
