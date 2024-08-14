/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.controllers;

import com.google.common.annotations.VisibleForTesting;
import com.google.protobuf.ByteString;
import io.dropwizard.auth.Auth;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import katie.MonitorKey;
import katie.MonitorProof;
import katie.MonitorResponse;
import katie.SearchResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.whispersystems.textsecuregcm.auth.AuthenticatedAccount;
import org.whispersystems.textsecuregcm.entities.KeyTransparencyMonitorRequest;
import org.whispersystems.textsecuregcm.entities.KeyTransparencyMonitorResponse;
import org.whispersystems.textsecuregcm.entities.KeyTransparencySearchRequest;
import org.whispersystems.textsecuregcm.entities.KeyTransparencySearchResponse;
import org.whispersystems.textsecuregcm.keytransparency.KeyTransparencyServiceClient;
import org.whispersystems.textsecuregcm.limits.RateLimitedByIp;
import org.whispersystems.textsecuregcm.limits.RateLimiters;
import org.whispersystems.textsecuregcm.util.ExceptionUtils;
import org.whispersystems.websocket.auth.ReadOnly;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import javax.ws.rs.BadRequestException;
import javax.ws.rs.ForbiddenException;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.ServerErrorException;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

@Path("/v1/key-transparency")
@Tag(name = "KeyTransparency")
public class KeyTransparencyController {

  private static final Logger LOGGER = LoggerFactory.getLogger(KeyTransparencyController.class);
  private static final Duration KEY_TRANSPARENCY_RPC_TIMEOUT = Duration.ofSeconds(15);
  private static final byte USERNAME_PREFIX = (byte) 'u';
  private static final byte E164_PREFIX = (byte) 'n';
  @VisibleForTesting
  static final byte ACI_PREFIX = (byte) 'a';
  private final KeyTransparencyServiceClient keyTransparencyServiceClient;

  public KeyTransparencyController(
      final KeyTransparencyServiceClient keyTransparencyServiceClient) {
    this.keyTransparencyServiceClient = keyTransparencyServiceClient;
  }

  @Operation(
      summary = "Search for the given search keys in the key transparency log",
      description = """
          Enforced unauthenticated endpoint. Returns a response if all search keys exist in the key transparency log.
          """
  )
  @ApiResponse(responseCode = "200", description = "All search key lookups were successful", useReturnTypeSchema = true)
  @ApiResponse(responseCode = "403", description = "At least one search key lookup to value mapping was invalid")
  @ApiResponse(responseCode = "404", description = "At least one search key lookup did not find the key")
  @ApiResponse(responseCode = "413", description = "Ratelimited")
  @ApiResponse(responseCode = "422", description = "Invalid request format")
  @POST
  @Path("/search")
  @RateLimitedByIp(RateLimiters.For.KEY_TRANSPARENCY_SEARCH_PER_IP)
  @Produces(MediaType.APPLICATION_JSON)
  public KeyTransparencySearchResponse search(
      @ReadOnly @Auth final Optional<AuthenticatedAccount> authenticatedAccount,
      @NotNull @Valid final KeyTransparencySearchRequest request) {

    // Disallow clients from making authenticated requests to this endpoint
    requireNotAuthenticated(authenticatedAccount);

    try {
      final CompletableFuture<SearchResponse> aciSearchKeyResponseFuture = keyTransparencyServiceClient.search(
          getFullSearchKeyByteString(ACI_PREFIX, request.aci().toCompactByteArray()),
          request.lastTreeHeadSize(),
          KEY_TRANSPARENCY_RPC_TIMEOUT);

      final CompletableFuture<SearchResponse> e164SearchKeyResponseFuture = request.e164()
          .map(e164 -> keyTransparencyServiceClient.search(
              getFullSearchKeyByteString(E164_PREFIX, e164.getBytes(StandardCharsets.UTF_8)),
              request.lastTreeHeadSize(),
              KEY_TRANSPARENCY_RPC_TIMEOUT))
          .orElse(CompletableFuture.completedFuture(null));

      final CompletableFuture<SearchResponse> usernameHashSearchKeyResponseFuture = request.usernameHash()
          .map(usernameHash -> keyTransparencyServiceClient.search(
              getFullSearchKeyByteString(USERNAME_PREFIX, request.usernameHash().get()),
              request.lastTreeHeadSize(),
              KEY_TRANSPARENCY_RPC_TIMEOUT))
          .orElse(CompletableFuture.completedFuture(null));

      return CompletableFuture.allOf(aciSearchKeyResponseFuture, e164SearchKeyResponseFuture,
              usernameHashSearchKeyResponseFuture)
          .thenApply(ignored ->
              new KeyTransparencySearchResponse(aciSearchKeyResponseFuture.join(),
                  Optional.ofNullable(e164SearchKeyResponseFuture.join()),
                  Optional.ofNullable(usernameHashSearchKeyResponseFuture.join())))
          .join();
    } catch (final CancellationException exception) {
      LOGGER.error("Unexpected cancellation from key transparency service", exception);
      throw new ServerErrorException(Response.Status.SERVICE_UNAVAILABLE, exception);
    } catch (final CompletionException exception) {
      handleKeyTransparencyServiceError(exception);
    }
    // This is unreachable
    return null;
  }

  @Operation(
      summary = "Monitor the given search keys in the key transparency log",
      description = """
          Enforced unauthenticated endpoint. Return proofs proving that the log tree
          has been constructed correctly in later entries for each of the given search keys .
          """
  )
  @ApiResponse(responseCode = "200", description = "All search keys exist in the log", useReturnTypeSchema = true)
  @ApiResponse(responseCode = "404", description = "At least one search key lookup did not find the key")
  @ApiResponse(responseCode = "413", description = "Ratelimited")
  @ApiResponse(responseCode = "422", description = "Invalid request format")
  @POST
  @Path("/monitor")
  @RateLimitedByIp(RateLimiters.For.KEY_TRANSPARENCY_MONITOR_PER_IP)
  @Produces(MediaType.APPLICATION_JSON)
  public KeyTransparencyMonitorResponse monitor(
      @ReadOnly @Auth final Optional<AuthenticatedAccount> authenticatedAccount,
      @NotNull @Valid final KeyTransparencyMonitorRequest request) {

    // Disallow clients from making authenticated requests to this endpoint
    requireNotAuthenticated(authenticatedAccount);

    try {
      final List<MonitorKey> monitorKeys = new ArrayList<>(List.of(
          createMonitorKey(getFullSearchKeyByteString(ACI_PREFIX, request.aci().toCompactByteArray()),
              request.aciPositions())
      ));

      request.usernameHash().ifPresent(usernameHash ->
          monitorKeys.add(createMonitorKey(getFullSearchKeyByteString(USERNAME_PREFIX, usernameHash),
              request.usernameHashPositions().get()))
      );

      request.e164().ifPresent(e164 ->
          monitorKeys.add(
              createMonitorKey(getFullSearchKeyByteString(E164_PREFIX, e164.getBytes(StandardCharsets.UTF_8)),
                  request.e164Positions().get()))
      );

      final MonitorResponse monitorResponse = keyTransparencyServiceClient.monitor(
          monitorKeys,
          request.lastTreeHeadSize(),
          KEY_TRANSPARENCY_RPC_TIMEOUT).join();

      MonitorProof usernameHashMonitorProof = null;
      MonitorProof e164MonitorProof = null;

      // In the future we'll update KT's monitor response structure to enumerate each monitor key proof
      // rather than returning everything in a list
      if (monitorResponse.getContactProofsCount() == 3) {
        e164MonitorProof = monitorResponse.getContactProofs(1);
        usernameHashMonitorProof = monitorResponse.getContactProofs(2);
      } else if (monitorResponse.getContactProofsCount() == 2) {
        if (request.usernameHash().isPresent()) {
          usernameHashMonitorProof = monitorResponse.getContactProofs(1);
        } else if (request.e164().isPresent()) {
          e164MonitorProof = monitorResponse.getContactProofs(1);
        }
      }
      return new KeyTransparencyMonitorResponse(monitorResponse.getTreeHead(),
          monitorResponse.getContactProofs(0),
          Optional.ofNullable(e164MonitorProof),
          Optional.ofNullable(usernameHashMonitorProof),
          monitorResponse.getInclusionList().stream().map(ByteString::toByteArray).toList());
    } catch (final CancellationException exception) {
      LOGGER.error("Unexpected cancellation from key transparency service", exception);
      throw new ServerErrorException(Response.Status.SERVICE_UNAVAILABLE, exception);
    } catch (final CompletionException exception) {
      handleKeyTransparencyServiceError(exception);
    }
    // This is unreachable
    return null;
  }

  private void handleKeyTransparencyServiceError(final CompletionException exception) {
    final Throwable unwrapped = ExceptionUtils.unwrap(exception);

    if (unwrapped instanceof StatusRuntimeException e) {
      final Status.Code code = e.getStatus().getCode();
      final String description = e.getStatus().getDescription();
      switch (code) {
        case NOT_FOUND -> throw new NotFoundException(description);
        case PERMISSION_DENIED -> throw new ForbiddenException(description);
        case INVALID_ARGUMENT -> throw new WebApplicationException(description, 422);
        default -> throw new ServerErrorException(Response.Status.INTERNAL_SERVER_ERROR, unwrapped);
      }
    }
    LOGGER.error("Unexpected key transparency service failure", unwrapped);
    throw new ServerErrorException(Response.Status.INTERNAL_SERVER_ERROR, unwrapped);
  }

  private static MonitorKey createMonitorKey(final ByteString fullSearchKey, final List<Long> positions) {
    return MonitorKey.newBuilder()
        .setSearchKey(fullSearchKey)
        .addAllEntries(positions)
        .build();
  }

  private void requireNotAuthenticated(final Optional<AuthenticatedAccount> authenticatedAccount) {
    if (authenticatedAccount.isPresent()) {
      throw new BadRequestException("Endpoint requires unauthenticated access");
    }
  }

  @VisibleForTesting
  static ByteString getFullSearchKeyByteString(final byte prefix, final byte[] searchKeyBytes) {
    final ByteBuffer fullSearchKeyBuffer = ByteBuffer.allocate(searchKeyBytes.length + 1);
    fullSearchKeyBuffer.put(prefix);
    fullSearchKeyBuffer.put(searchKeyBytes);
    fullSearchKeyBuffer.flip();

    return ByteString.copyFrom(fullSearchKeyBuffer.array());
  }
}