import { lstatSync, mkdtempSync, readdirSync, realpathSync, renameSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { isDeepStrictEqual } from 'node:util';
import {
  checked, compilerRepository, contractsVersion, diagnosticsFile, generatedFiles, gitInputs, inputPaths, profile, readRegularFile,
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

function verifyRun(run, source, compilerSha, id) {
  requireCondition(String(run.id) === id && run.event === 'workflow_dispatch' &&
    run.path?.split('@', 1)[0] === workflowPath && run.head_sha === source.sha &&
    run.head_repository?.full_name?.toLowerCase() === source.repository.toLowerCase(),
  'GitHub returned a run for a different workflow, repository or source revision.');
  requireCondition(run.run_attempt === 1, 'This run was retried. Dispatch a new generation.');
  const pinned = `${compilerRepository}/${workflowPath}@${compilerSha}`;
  requireCondition(run.status === 'completed', 'The generation run has not completed.');
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

function verifyDownload(download, source, compilerSha, id, conclusion) {
  requireCondition(lstatSync(download).isDirectory(), 'Expected a regular artifact directory.');
  const receiptBytes = readRegularFile(join(download, receiptFile));
  const receipt = JSON.parse(receiptBytes);
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
  const resultFiles = success ? [...generatedFiles, diagnosticsFile] : [diagnosticsFile];
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
      projection.kind === 'compiler-node-local' &&
      projection.provenance?.dslSha256 === source.inputs[0].sha256 &&
      projection.provenance?.bindingsSha256 === source.inputs[1].sha256,
    'Compiler projection does not match the requested profile or committed inputs.');
  }
  return { ...files, [receiptFile]: receiptBytes };
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
    requireCondition(/^HTTP\/\S+ 404\b/.test(probe.stdout?.toString() ?? ''),
      `Could not read generation run ${id}: ${probe.error?.message ?? probe.stderr?.toString().trim()}`);
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
} = {}) {
  requireCondition(validRepository(repository) && ref && !ref.startsWith('-') &&
    validSha(sourceSha) && validSha(compilerSha) && outputArg,
  'Usage: node remote-node-generation.mjs OWNER/REPO REF SOURCE_SHA COMPILER_SHA NEW_OUTPUT_DIR [PROJECT_DIR]');
  checked('git', ['check-ref-format', `refs/heads/${ref}`], { cwd });
  cwd ??= checked('git', ['rev-parse', '--show-toplevel']).toString().trim();
  const output = resolve(outputArg);
  requireNewOutput(output);
  const inputs = committedInputs(cwd, sourceSha, project);
  const endpoint = `repos/${repository}`;
  const remote = api(`${endpoint}/commits/${encodeURIComponent(ref)}`);
  requireCondition(remote.sha === sourceSha, 'Push the committed revision and use a branch or tag pointing to its exact SHA.');
  const source = sourceIdentity(repository, sourceSha, project, inputs);
  const dispatch = api(`${endpoint}/actions/workflows/${workflow}/dispatches`, {
    ref, return_run_details: true, inputs: { source_sha: sourceSha, compiler_sha: compilerSha, project },
  });
  const id = String(dispatch.workflow_run_id);
  requireCondition(/^\d+$/.test(id), 'GitHub did not return the dispatched run ID. Check Actions before retrying.');
  const runEndpoint = `${endpoint}/actions/runs/${id}`;
  const runUrl = `https://github.com/${repository}/actions/runs/${id}`;
  console.log(`Generation run ${runUrl}`);
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
    const files = verifyDownload(unpacked, source, compilerSha, id, run.conclusion);
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
if (process.argv[1] && realpathSync(process.argv[1]) === realpathSync(fileURLToPath(import.meta.url))) {
  try {
    const version = checked('gh', ['--version']).toString();
    const [, major, minor] = /^gh version (\d+)\.(\d+)/.exec(version) ?? [];
    requireCondition(Number(major) > 2 || (Number(major) === 2 && Number(minor) >= 100),
      'Install GitHub CLI 2.100 or newer and sign in with gh auth login.');
    console.log(`Verified generated bindings in ${generateRemote(process.argv.slice(2))}`);
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
