import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { lstatSync, readFileSync } from 'node:fs';

export const compilerRepository = 'The-Pipeline-Framework/pipelineframework-compiler';
export const contractsVersion = '26.11.1';
export const workflow = 'tpf-node-generation.yml';
export const workflowPath = `.github/workflows/${workflow}`;
export const profile = 'tpf-polyglot-unary-policy/1';
export const receiptFile = 'generation-receipt.json';
export const diagnosticsFile = 'diagnostics.txt';
export const generatedFiles = ['application.ts', 'models.ts', 'projection.json'];
export const inputNames = ['pipeline.yaml', 'native-bindings.json'];
export const limit = 8 * 1024 * 1024;

export const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');

export function requireCondition(condition, message) {
  if (!condition) throw new Error(message);
}

export function validSha(value) {
  return typeof value === 'string' && /^[0-9a-f]{40}$/.test(value);
}

export function validDigest(value) {
  return typeof value === 'string' && /^[0-9a-f]{64}$/.test(value);
}

export function validRepository(value) {
  return typeof value === 'string' && /^[\w-]+\/[\w.-]+$/.test(value) && !/\/\.{1,2}$/.test(value);
}

export function inputPaths(project) {
  const segment = /^[A-Za-z0-9_-][A-Za-z0-9_.-]*$/;
  requireCondition(project === '.' || (typeof project === 'string' && project.length <= 200 && project.split('/').every(p => segment.test(p))),
    'Use a repository-relative project directory without dot segments, symlinks or special characters.');
  return inputNames.map(name => project === '.' ? name : `${project}/${name}`);
}

export function run(command, args, options = {}) {
  return spawnSync(command, args, { maxBuffer: limit, ...options });
}

export function checked(command, args, options) {
  const result = run(command, args, options);
  requireCondition(result.status === 0,
    `${command} ${args[0]} failed: ${result.error?.message ?? result.stderr?.toString().trim() ?? result.signal}`);
  return result.stdout;
}

export function readRegularFile(path) {
  const stat = lstatSync(path);
  requireCondition(stat.isFile() && stat.size <= limit, `Expected a regular file within the size limit: ${path}`);
  return readFileSync(path);
}

export function gitInputs(cwd, sha, project) {
  requireCondition(validSha(sha), 'Provide an exact 40-character Git commit SHA.');
  return inputPaths(project).map(path => {
    const entry = checked('git', ['ls-tree', '-z', sha, '--', path], { cwd }).toString();
    const blob = /^100(?:644|755) blob ([0-9a-f]{40})\t([^\0]+)\0$/.exec(entry);
    requireCondition(blob?.[2] === path, `Expected a committed regular file at ${path} in ${sha}.`);
    // Read the Git blob instead of opening an application checkout path.
    const bytes = checked('git', ['cat-file', 'blob', blob[1]], { cwd });
    return { path, bytes, sha256: sha256(bytes) };
  });
}

export function sourceIdentity(repository, sha, project, inputs) {
  return { repository: repository.toLowerCase(), sha, project, inputs: inputs.map(({ path, sha256 }) => ({ path, sha256 })) };
}
