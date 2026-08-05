/**
 * @file Exports an ACM-managed service certificate into Secrets Manager and
 * restarts the long-running ECS services so new tasks resolve the new
 * AWSCURRENT secret version.
 *
 * The function returns only the secret version identifier and restart count.
 * Certificate material, the export passphrase, and the private key never cross
 * the Lambda response boundary and therefore never enter Terraform state.
 */

import {
  ACMClient,
  ExportCertificateCommand,
} from "@aws-sdk/client-acm";
import {
  ECSClient,
  ListServicesCommand,
  UpdateServiceCommand,
} from "@aws-sdk/client-ecs";
import {
  PutSecretValueCommand,
  SecretsManagerClient,
} from "@aws-sdk/client-secrets-manager";
import { createPrivateKey, randomBytes } from "node:crypto";

const acm = new ACMClient({});
const ecs = new ECSClient({});
const secretsManager = new SecretsManagerClient({});

/**
 * Reads one required environment setting.
 *
 * @param {string} name Environment-variable name to read.
 * @returns {string} Non-empty configured value.
 * @throws {Error} When the variable is absent or blank.
 */
function requiredEnvironment(name) {
  const value = process.env[name]?.trim();
  if (!value) {
    throw new Error(`Required environment setting ${name} is missing.`);
  }
  return value;
}

/**
 * Converts ACM's passphrase-encrypted PKCS#8 key into the unencrypted PEM text
 * Spring Boot accepts from its certificate-private-key property.
 *
 * @param {string} encryptedPrivateKey PEM-encoded encrypted private key.
 * @param {Buffer} passphrase One-use passphrase supplied to ACM export.
 * @returns {string} Unencrypted PKCS#8 PEM text.
 * @throws {Error} When Node cannot parse or decrypt ACM's response.
 */
function decryptPrivateKey(encryptedPrivateKey, passphrase) {
  const key = createPrivateKey({
    key: encryptedPrivateKey,
    format: "pem",
    passphrase,
  });
  return key.export({ format: "pem", type: "pkcs8" }).toString();
}

/**
 * Forces every long-running service in the configured cluster to replace its
 * tasks after AWSCURRENT changes.
 *
 * @param {string} clusterArn Exact ECS cluster ARN.
 * @returns {Promise<number>} Number of services asked to deploy again.
 * @throws {Error} When service discovery or an update request fails.
 */
async function restartServices(clusterArn) {
  let nextToken;
  let restarted = 0;

  do {
    const page = await ecs.send(
      new ListServicesCommand({ cluster: clusterArn, nextToken }),
    );
    for (const service of page.serviceArns ?? []) {
      await ecs.send(
        new UpdateServiceCommand({
          cluster: clusterArn,
          service,
          forceNewDeployment: true,
        }),
      );
      restarted += 1;
    }
    nextToken = page.nextToken;
  } while (nextToken);

  return restarted;
}

/**
 * Exports the current certificate, stores its full chain and unencrypted
 * private key as one JSON secret, and restarts ECS services.
 *
 * @param {Record<string, unknown>} _event Invocation payload. ACM renewal and
 * initial Terraform invocations share the same behavior, so no event content
 * is trusted or required.
 * @returns {Promise<{secretVersionId: string, restartedServices: number}>}
 * Non-secret evidence used by Terraform to order initial service creation.
 * @throws {Error} When ACM export, secret publication, or ECS restart fails.
 */
export async function handler(_event) {
  const certificateArn = requiredEnvironment("CERTIFICATE_ARN");
  const secretArn = requiredEnvironment("SECRET_ARN");
  const clusterArn = requiredEnvironment("ECS_CLUSTER_ARN");
  const passphrase = randomBytes(32);

  try {
    const exported = await acm.send(
      new ExportCertificateCommand({
        CertificateArn: certificateArn,
        Passphrase: passphrase,
      }),
    );
    if (!exported.Certificate || !exported.PrivateKey) {
      throw new Error("ACM export omitted certificate or private-key material.");
    }

    const certificate = [exported.Certificate, exported.CertificateChain]
      .filter(Boolean)
      .map((value) => value.trim())
      .join("\n");
    const privateKey = decryptPrivateKey(exported.PrivateKey, passphrase);
    const published = await secretsManager.send(
      new PutSecretValueCommand({
        SecretId: secretArn,
        SecretString: JSON.stringify({
          certificate,
          private_key: privateKey,
        }),
        VersionStages: ["AWSCURRENT"],
      }),
    );
    if (!published.VersionId) {
      throw new Error("Secrets Manager did not return a version identifier.");
    }

    return {
      secretVersionId: published.VersionId,
      restartedServices: await restartServices(clusterArn),
    };
  } catch (error) {
    // Assumptions: AWS errors can contain request metadata but no PEM material
    // in these operations. Logging only the stable name/code still prevents a
    // future SDK message change from echoing protected content.
    console.error(
      JSON.stringify({
        event: "service-tls-export-failed",
        errorName: error?.name ?? "Error",
        errorCode: error?.Code ?? error?.code ?? "UNKNOWN",
      }),
    );
    throw new Error("Service TLS certificate publication failed.");
  } finally {
    passphrase.fill(0);
  }
}
