import { lstatSync, mkdtempSync, readdirSync, realpathSync, renameSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { isDeepStrictEqual, parseArgs } from 'node:util';
import {
  bindingTarget, checked, compilerRepository, contractsVersion, diagnosticsFile, gitInputs, inputPaths, profile, readRegularFile,
  receiptFile, requireCondition, run, sha256, sourceIdentity, validDigest, validRepository, validSha, workflow, workflowPath,
} from './node-generation-common.mjs';

function ghApi(endpoint, body) {
  const flags = body ? ['--method', 'POST', '--input', '-'] : [];
  const response = checked('gh', ['api', '--hostname', 'github.com',
    '--header', 'X-GitHub-Api-Version: 2022-11-28', ...flags, endpoint], {
    input: body ? JSON.stringify(body) : undefined,
  }).toString();
  return response ? JSON.parse(response) : {};
}

function requireNewOutput(output) {
  requireCondition(!lstatSync(output, { throwIfNoEntry: false }),
    `Output already exists at ${output}. Choose a new directory for this generation.`);
}

function verifyRun(run, source, compilerSha, id, completed = true) {
  requireCondition(String(run.id) === id && run.event === 'workflow_dispatch' &&
    run.path?.split('@', 1)[0] === workflowPath && run.head_sha === source.sha &&
    run.head_repository?.full_name?.toLowerCase() === source.repository.toLowerCase(),
  'GitHub returned a run for a different workflow, repository or source revision.');
  requireCondition(run.run_attempt === 1, 'This run was retried. Dispatch a new generation.');
  const pinned = `${compilerRepository}/${workflowPath}@${compilerSha}`;
  requireCondition(!completed || run.status === 'completed', 'The generation run has not completed.');
  requireCondition(run.referenced_workflows?.some(reference => reference.path === pinned && reference.sha === compilerSha),
    'The run did not use the requested compiler workflow revision.');
}

function verifyCompiler(compiler) {
  requireCondition(/^pipelineframework-compiler-[0-9.]+(?:-SNAPSHOT)?\.jar$/.test(compiler.artifact) &&
    validDigest(compiler.sha256) && compiler.contractsVersion === contractsVersion &&
    Array.isArray(compiler.dependencies) && compiler.dependencies.length > 0,
  'Generation receipt is missing compiler or dependency identity.');
  const seen = new Set();
  for (const dependency of compiler.dependencies) {
    const artifact = dependency.artifact;
    requireCondition(typeof artifact === 'string' && artifact.endsWith('.jar') && !artifact.includes('SNAPSHOT') &&
      artifact.split('/').every(segment => /^[\w-][\w.+-]*$/.test(segment)) &&
      !seen.has(artifact) && validDigest(dependency.sha256),
    'Generation receipt has an invalid dependency identity.');
    seen.add(artifact);
  }
}

function verifyDownload(download, source, compilerSha, id, conclusion, target, binding) {
  requireCondition(lstatSync(download).isDirectory(), 'Expected a regular artifact directory.');
  const receiptBytes = readRegularFile(join(download, receiptFile));
  const receipt = JSON.parse(receiptBytes);
  const receivedTarget = Object.hasOwn(receipt, 'target') ? receipt.target : 'node';
  requireCondition(receivedTarget === target, 'Generation receipt does not match the requested target.');
  requireCondition(receipt.schemaVersion === 1 && receipt.profile === profile &&
    receipt.run?.id === id && receipt.run.attempt === '1' &&
    receipt.compiler?.repository === compilerRepository && receipt.compiler?.sha === compilerSha,
  'Generation receipt does not match this run, compiler revision or profile.');
  const success = conclusion === 'success';
  requireCondition(receipt.status === (success ? 'success' : 'failed'),
    'Generation receipt and workflow conclusion disagree.');
  if (success) {
    verifyCompiler(receipt.compiler);
    requireCondition(isDeepStrictEqual(receipt.source, source), 'Generation receipt does not match the committed inputs.');
  } else {
    requireCondition(receipt.source?.repository === source.repository && receipt.source?.sha === source.sha &&
      receipt.source?.project === source.project &&
      (!receipt.source.inputs || isDeepStrictEqual(receipt.source.inputs, source.inputs)),
    'Failed generation receipt does not match the requested source revision.');
  }
  const resultFiles = success ? [...binding.files, diagnosticsFile] : [diagnosticsFile];
  requireCondition(isDeepStrictEqual(readdirSync(download).sort(), [...resultFiles, receiptFile].sort()) &&
    isDeepStrictEqual(Object.keys(receipt.files ?? {}).sort(), resultFiles.sort()),
  'Generation artifact contains an unexpected set of files.');
  const files = Object.fromEntries(resultFiles.map(name => {
    const bytes = readRegularFile(join(download, name));
    requireCondition(receipt.files[name] === sha256(bytes), `Generation artifact digest mismatch for ${name}.`);
    return [name, bytes];
  }));
  if (success) {
    const projection = JSON.parse(files['projection.json']);
    requireCondition(projection.schemaVersion === 1 && projection.profile === profile &&
      projection.kind === binding.kind &&
      projection.provenance?.dslSha256 === source.inputs[0].sha256 &&
      projection.provenance?.bindingsSha256 === source.inputs[1].sha256,
    'Compiler projection does not match the requested profile or committed inputs.');
    if (Object.hasOwn(files, 'compiled-truth.zip')) verifyCompiledTruth(download, files);
  }
  return { ...files, [receiptFile]: receiptBytes };
}

function verifyCompiledTruth(download, files) {
  const expected = {
    'META-INF/pipeline/native-execution-projection.json': files['projection.json'],
    'META-INF/pipeline/pipeline-contract.json': files['pipeline-contract.json'],
  };
  const archive = 'compiled-truth.zip';
  const options = { cwd: download, timeout: 10_000 };
  const listing = run('unzip', ['-Z1', archive], options);
  requireCondition(listing.error?.code !== 'ENOENT', 'Install unzip to verify Compiled Truth for a worker target.');
  requireCondition(listing.status === 0, 'Invalid Compiled Truth ZIP.');
  const entries = listing.stdout.toString('utf8').split(/\r?\n/);
  requireCondition(entries.pop() === '' && isDeepStrictEqual(entries.sort(), Object.keys(expected).sort()),
    'Unexpected Compiled Truth resources.');
  for (const [name, bytes] of Object.entries(expected)) {
    const result = run('unzip', ['-p', archive, name], options);
    requireCondition(result.status === 0 && result.stdout.equals(bytes),
      `Compiled Truth resource differs from generated output: ${name}.`);
  }
}

function committedInputs(cwd, sha, project) {
  requireCondition(checked('git', ['rev-parse', 'HEAD'], { cwd }).toString().trim() === sha,
    'Check out the requested application revision before generating.');
  requireCondition(!checked('git', ['status', '--porcelain', '--', ...inputPaths(project)], { cwd }).length,
    'Commit changes to pipeline.yaml and native-bindings.json before generating.');
  return gitInputs(cwd, sha, project);
}

export function watchRun(repository, id, {
  execute = run,
  pause = milliseconds => Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, milliseconds),
} = {}) {
  // Dispatch may return the run ID before the Actions read endpoint can see it.
  const endpoint = `repos/${repository}/actions/runs/${id}?exclude_pull_requests=true`;
  for (const delay of [1000, 2000, 4000, 8000, 0]) {
    const probe = execute('gh', ['api', '--hostname', 'github.com', '--include', '--silent', endpoint],
      { timeout: 10_000 });
    if (probe.status === 0) break;
    // gh may fail while decoding an HTTP error body. Retain only the status code,
    // not response headers, so the failure remains diagnosable without leaking metadata.
    const status = /^HTTP\/\S+ (\d{3})\b/.exec(probe.stdout?.toString() ?? '')?.[1];
    requireCondition(status === '404',
      `Could not read generation run ${id} (${status ? `HTTP ${status}` : 'HTTP status unavailable'}): ` +
      `${probe.error?.message ?? probe.stderr?.toString().trim()}. Inspect the linked run before dispatching again.`);
    requireCondition(delay > 0,
      `Generation run ${id} is not visible yet. Check the linked GitHub Actions run before dispatching again.`);
    pause(delay);
  }
  const watched = execute('gh', ['run', 'watch', '--repo', `github.com/${repository}`, id, '--interval', '10'], {
    stdio: 'inherit', timeout: 30 * 60 * 1000,
  });
  requireCondition(watched.status === 0, 'Could not watch the generation run. Check its GitHub Actions page.');
}

export function generateRemote([repository, ref, sourceSha, compilerSha, outputArg, project = '.'], {
  api = ghApi,
  watch = watchRun,
  download = (repository, id, destination) => checked('gh', [
    'run', 'download', '--repo', `github.com/${repository}`, id, '--name', `tpf-node-generation-${id}-1`, '--dir', destination,
  ]),
  cwd,
  runId,
  target = 'node',
} = {}) {
  const binding = bindingTarget(target);
  requireCondition(validRepository(repository) && ref && !ref.startsWith('-') &&
    validSha(sourceSha) && validSha(compilerSha) && outputArg,
  'Usage: node remote-node-generation.mjs OWNER/REPO REF SOURCE_SHA COMPILER_SHA NEW_OUTPUT_DIR [PROJECT_DIR]');
  requireCondition(runId === undefined || (typeof runId === 'string' && /^[1-9][0-9]*$/.test(runId)),
    'Provide a positive decimal --run-id.');
  checked('git', ['check-ref-format', `refs/heads/${ref}`], { cwd });
  cwd ??= checked('git', ['rev-parse', '--show-toplevel']).toString().trim();
  const output = resolve(outputArg);
  requireNewOutput(output);
  const inputs = committedInputs(cwd, sourceSha, project);
  const endpoint = `repos/${repository}`;
  const source = sourceIdentity(repository, sourceSha, project, inputs);
  let id = runId;
  // A branch may move after dispatch. Recovery is bound to the original run
  // and immutable inputs, not the branch's current tip.
  if (id === undefined) {
    const remote = api(`${endpoint}/commits/${encodeURIComponent(ref)}`);
    requireCondition(remote.sha === sourceSha, 'Push the committed revision and use a branch or tag pointing to its exact SHA.');
    const dispatch = api(`${endpoint}/actions/workflows/${workflow}/dispatches`, {
      ref, return_run_details: true, inputs: {
        source_sha: sourceSha, compiler_sha: compilerSha, project, ...(target === 'node' ? {} : { target }),
      },
    });
    id = String(dispatch.workflow_run_id);
    requireCondition(/^\d+$/.test(id), 'GitHub did not return the dispatched run ID. Check Actions before retrying.');
  }
  const runEndpoint = `${endpoint}/actions/runs/${id}`;
  const runUrl = `https://github.com/${repository}/actions/runs/${id}`;
  console.log(`Generation run ${runUrl}`);
  console.log(`To resume without dispatching, repeat this command with --run-id ${id} and the same source/compiler/project/target.`);
  if (runId !== undefined) verifyRun(api(runEndpoint), source, compilerSha, id, false);
  watch(repository, id);
  const run = api(runEndpoint);
  verifyRun(run, source, compilerSha, id);

  const temporary = mkdtempSync(join(dirname(output), '.tpf-generation-'));
  try {
    const unpacked = join(temporary, 'download');
    try {
      download(repository, id, unpacked);
    } catch (error) {
      throw new Error(`Could not retrieve the generation artifact. Inspect ${runUrl}. ${error.message}`);
    }
    const files = verifyDownload(unpacked, source, compilerSha, id, run.conclusion, target, binding);
    const latest = api(runEndpoint);
    verifyRun(latest, source, compilerSha, id);
    requireCondition(latest.conclusion === run.conclusion, 'The generation run conclusion changed during download.');
    if (run.conclusion !== 'success') {
      // Do not emit control characters from remote diagnostics to the terminal.
      const diagnostics = files[diagnosticsFile].toString().replace(/[\x00-\x08\x0b-\x1f\x7f-\x9f]/g, '');
      throw new Error(`Generation failed. Inspect ${runUrl}.\n${diagnostics}`);
    }
    committedInputs(cwd, sourceSha, project);
    const staged = mkdtempSync(join(temporary, 'verified-'));
    for (const [name, bytes] of Object.entries(files)) writeFileSync(join(staged, name), bytes, { flag: 'wx' });
    requireNewOutput(output);
    renameSync(staged, output);
  } finally {
    rmSync(temporary, { recursive: true, force: true });
  }
  return output;
}

// Node resolves module URLs through symlinks, but argv can retain the caller's path
// (for example /tmp versus /private/tmp on macOS). Compare filesystem identities.
function isCliEntryPoint() {
  if (!process.argv[1]) return false;
  try {
    return realpathSync(process.argv[1]) === realpathSync(fileURLToPath(import.meta.url));
  } catch {
    // Importers may use a synthetic or no-longer-existing entry-point path.
    return false;
  }
}

if (isCliEntryPoint()) {
  try {
    const version = checked('gh', ['--version']).toString();
    const [, major, minor] = /^gh version (\d+)\.(\d+)/.exec(version) ?? [];
    requireCondition(Number(major) > 2 || (Number(major) === 2 && Number(minor) >= 100),
      'Install GitHub CLI 2.100 or newer and sign in with gh auth login.');
    const { values, positionals } = parseArgs({
      options: { 'run-id': { type: 'string' }, target: { type: 'string' } }, allowPositionals: true,
    });
    requireCondition(positionals.length >= 5 && positionals.length <= 6,
      'Usage: node remote-node-generation.mjs OWNER/REPO REF SOURCE_SHA COMPILER_SHA NEW_OUTPUT_DIR '
      + '[PROJECT_DIR] [--target TARGET] [--run-id RUN_ID]');
    console.log(`Verified generated bindings in ${generateRemote(positionals, { runId: values['run-id'], target: values.target })}`);
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
