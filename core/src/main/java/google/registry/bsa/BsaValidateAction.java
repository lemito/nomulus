// Copyright 2024 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.bsa;

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.bsa.persistence.DownloadScheduler.fetchMostRecentDownloadJobIdIfCompleted;
import static google.registry.bsa.persistence.Queries.batchReadBsaLabelText;
import static google.registry.request.Action.Method.GET;
import static google.registry.request.Action.Method.POST;
import static javax.servlet.http.HttpServletResponse.SC_OK;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.common.collect.Sets.SetView;
import com.google.common.flogger.FluentLogger;
import google.registry.config.RegistryConfig.Config;
import google.registry.request.Action;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import java.util.Optional;
import java.util.stream.Stream;
import javax.inject.Inject;

/** Validates the BSA data in the database against the most recent block lists. */
@Action(
    service = Action.Service.BSA,
    path = BsaValidateAction.PATH,
    method = {GET, POST},
    auth = Auth.AUTH_API_ADMIN)
public class BsaValidateAction implements Runnable {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  static final String PATH = "/_dr/task/bsaValidate";
  private final GcsClient gcsClient;
  private final int transactionBatchSize;
  private final BsaLock bsaLock;
  private final Response response;

  @Inject
  BsaValidateAction(
      GcsClient gcsClient,
      @Config("bsaTxnBatchSize") int transactionBatchSize,
      BsaLock bsaLock,
      Response response) {
    this.gcsClient = gcsClient;
    this.transactionBatchSize = transactionBatchSize;
    this.bsaLock = bsaLock;
    this.response = response;
  }

  @Override
  public void run() {
    try {
      if (!bsaLock.executeWithLock(this::runWithinLock)) {
        logger.atInfo().log("Cannot execute action. Other BSA related task is executing.");
        // TODO(blocked by go/r3pr/2354): send email
      }
    } catch (Throwable throwable) {
      logger.atWarning().withCause(throwable).log("Failed to update block lists.");
      // TODO(blocked by go/r3pr/2354): send email
    }
    // Always return OK. No need to retry since all queries and GCS accesses are already
    // implicitly retried.
    response.setStatus(SC_OK);
  }

  /** Executes the validation action while holding the BSA lock. */
  Void runWithinLock() {
    Optional<String> downloadJobName = fetchMostRecentDownloadJobIdIfCompleted();
    if (downloadJobName.isEmpty()) {
      logger.atInfo().log("Cannot validate: latest download not found or unfinished.");
      return null;
    }
    logger.atInfo().log("Validating BSA with latest download: %s", downloadJobName.get());

    ImmutableList.Builder<String> errors = new ImmutableList.Builder();
    errors.addAll(checkBsaLabels(downloadJobName.get()));

    emailValidationResults(downloadJobName.get(), errors.build());
    logger.atInfo().log("Finished validating BSA with latest download: %s", downloadJobName.get());
    return null;
  }

  void emailValidationResults(String job, ImmutableList<String> errors) {
    // TODO(blocked by go/r3pr/2354): send email
  }

  ImmutableList<String> checkBsaLabels(String jobName) {
    ImmutableSet<String> downloadedLabels = fetchDownloadedLabels(jobName);
    ImmutableSet<String> persistedLabels = fetchPersistedLabels(transactionBatchSize);
    ImmutableList.Builder<String> errors = new ImmutableList.Builder<>();

    int nErrorExamples = 10;
    SetView<String> missingLabels = Sets.difference(downloadedLabels, persistedLabels);
    if (!missingLabels.isEmpty()) {
      String examples = Joiner.on(',').join(Iterables.limit(missingLabels, nErrorExamples));
      String errorMessage =
          String.format(
              "Found %d missing labels in the DB. Examples: [%s]", missingLabels.size(), examples);
      logger.atInfo().log(errorMessage);
      errors.add(errorMessage);
    }
    SetView<String> unexpectedLabels = Sets.difference(persistedLabels, downloadedLabels);
    if (!unexpectedLabels.isEmpty()) {
      String examples = Joiner.on(',').join(Iterables.limit(unexpectedLabels, nErrorExamples));
      String errorMessage =
          String.format(
              "Found %d unexpected labels in the DB. Examples: [%s]",
              unexpectedLabels.size(), examples);
      logger.atInfo().log(errorMessage);
      errors.add(errorMessage);
    }
    return errors.build();
  }

  /** Returns unique labels across all block lists in the download specified by {@code jobName}. */
  ImmutableSet<String> fetchDownloadedLabels(String jobName) {
    ImmutableSet.Builder<String> labelsBuilder = new ImmutableSet.Builder<>();
    for (BlockListType blockListType : BlockListType.values()) {
      try (Stream<String> lines = gcsClient.readBlockList(jobName, blockListType)) {
        lines.skip(1).map(BsaValidateAction::parseBlockListLine).forEach(labelsBuilder::add);
      }
    }
    return labelsBuilder.build();
  }

  ImmutableSet<String> fetchPersistedLabels(int batchSize) {
    ImmutableSet.Builder<String> labelsBuilder = new ImmutableSet.Builder<>();
    ImmutableList<String> batch;
    Optional<String> lastRead = Optional.empty();
    do {
      batch = batchReadBsaLabelText(lastRead, batchSize);
      batch.forEach(labelsBuilder::add);
      if (!batch.isEmpty()) {
        lastRead = Optional.of(Iterables.getLast(batch));
      }
    } while (batch.size() == batchSize);
    return labelsBuilder.build();
  }

  static String parseBlockListLine(String line) {
    int firstComma = line.indexOf(',');
    checkArgument(firstComma > 0, "Invalid block list line: %s", line);
    return line.substring(0, firstComma);
  }
}
