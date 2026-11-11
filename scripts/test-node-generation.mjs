import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, readdirSync, readFileSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import {
  checked, compilerRepository, contractsVersion, diagnosticsFile, generatedFiles, gitInputs, profile,
  receiptFile, sha256, sourceIdentity, workflowPath,
} from './node-generation-common.mjs';
import { stageGitInputs } from './node-generation-host.mjs';
import { generateRemote, watchRun } from './remote-node-generation.mjs';

const repository = 'owner/app';
const compilerSha = 'a'.repeat(40);
const runId = '123456';

test('CLI enters argument validation through both real and symlinked script paths', t => {
  const directory = mkdtempSync(join(tmpdir(), 'tpf-client-entry-'));
  t.after(() => rmSync(directory, {recursive: true, force: true}));
  // No GitHub account or network is involved: validation only needs the CLI version.
  writeFileSync(join(directory, 'gh'), '#!/bin/sh\nprintf "gh version 2.100.0\\n"\n', {mode: 0o755});
  const source = fileURLToPath(new URL('./remote-node-generation.mjs', import.meta.url));
  const alias = join(directory, 'client.mjs');
  symlinkSync(source, alias);
  for (const path of [source, alias]) {
    const result = spawnSync(process.execPath, [path], {
      env: {...process.env, PATH: directory}, encoding: 'utf8', timeout: 10_000,
    });
    assert.equal(result.status, 1, `${path}: must not silently succeed without entering the CLI`);
    assert.match(result.stderr, /Usage: node remote-node-generation.mjs/);
    assert.equal(result.stdout, '');
  }
});

function fixture(t, { symlinkInput = false } = {}) {
  const cwd = mkdtempSync(join(tmpdir(), 'tpf-generation-test-'));
  t.after(() => rmSync(cwd, { recursive: true, force: true }));
  const git = (...args) => checked('git', args, { cwd }).toString().trim();
  git('init', '-q');
  writeFileSync(join(cwd, 'native-bindings.json'), '{}\n');
  if (symlinkInput) symlinkSync('native-bindings.json', join(cwd, 'pipeline.yaml'));
  else writeFileSync(join(cwd, 'pipeline.yaml'), 'version: 3\n');
  git('add', '--', 'pipeline.yaml', 'native-bindings.json');
  git('-c', 'user.name=Generation test', '-c', 'user.email=test@example.invalid', '-c', 'commit.gpgsign=false',
    'commit', '-qm', 'Generation inputs');
  const sourceSha = git('rev-parse', 'HEAD');
  const source = symlinkInput ? undefined : sourceIdentity(repository, sourceSha, '.', gitInputs(cwd, sourceSha, '.'));
  const output = join(cwd, 'generated');
  const args = [repository, 'test/branch', sourceSha, compilerSha, output];
  const run = {
    id: Number(runId), path: workflowPath, event: 'workflow_dispatch', run_attempt: 1,
    status: 'completed', conclusion: 'success', head_sha: sourceSha, head_repository: { full_name: repository },
    referenced_workflows: [{ path: `${compilerRepository}/${workflowPath}@${compilerSha}`, sha: compilerSha }],
  };
  const files = {
    'application.ts': 'export const application = {};\n',
    'models.ts': 'export type Value = string;\n',
    'projection.json': JSON.stringify({ schemaVersion: 1, profile, kind: 'compiler-node-local', provenance: {
      dslSha256: source?.inputs[0].sha256, bindingsSha256: source?.inputs[1].sha256,
    } }),
    [diagnosticsFile]: '',
  };
  const receipt = {
    schemaVersion: 1, profile, status: 'success', run: { id: runId, attempt: '1' }, source,
    compiler: {
      repository: compilerRepository, sha: compilerSha, artifact: 'pipelineframework-compiler-26.11.2-SNAPSHOT.jar',
      sha256: 'b'.repeat(64), contractsVersion,
      dependencies: [{ artifact: 'org/pipelineframework/compiler/1.0.0/compiler-1.0.0.jar', sha256: 'c'.repeat(64) }],
    },
    files: Object.fromEntries(Object.entries(files).map(([name, bytes]) => [name, sha256(bytes)])),
  };
  const events = [];
  const mock = {
    cwd,
    api(path, body) {
      events.push(path);
      if (path.includes('/commits/')) return { sha: sourceSha };
      if (path.endsWith('/dispatches')) {
        assert.deepEqual(body, { ref: 'test/branch', return_run_details: true,
          inputs: { source_sha: sourceSha, compiler_sha: compilerSha, project: '.' } });
        return { workflow_run_id: Number(runId) };
      }
      assert.equal(path, `repos/${repository}/actions/runs/${runId}`);
      return structuredClone(run);
    },
    watch(repo, id) {
      assert.equal(repo, repository);
      assert.equal(id, runId);
    },
    download(repo, id, destination) {
      assert.equal(repo, repository);
      assert.equal(id, runId);
      mkdirSync(destination);
      for (const [name, bytes] of Object.entries(files)) writeFileSync(join(destination, name), bytes);
      writeFileSync(join(destination, receiptFile), JSON.stringify(receipt));
    },
  };
  return { args, cwd, events, files, mock, output, receipt, run };
}

function absent(output) {
  assert.throws(() => readdirSync(output), { code: 'ENOENT' });
}

test('dispatches exact source, verifies this run and installs only the expected files', t => {
  const { args, events, files, mock, output } = fixture(t);
  assert.equal(generateRemote(args, mock), output);
  assert.deepEqual(readdirSync(output).sort(), [...generatedFiles, diagnosticsFile, receiptFile].sort());
  assert.equal(readFileSync(join(output, 'models.ts'), 'utf8'), files['models.ts']);
  assert.match(events[0], /commits\/test%2Fbranch$/);
  assert.equal(events.length, 4);
  assert.throws(() => generateRemote(args, mock), /Output already exists/);
  assert.equal(events.length, 4);
});

test('rejects an unpushed or moved ref before dispatch', t => {
  const { args, mock, output } = fixture(t);
  mock.api = path => {
    assert.match(path, /\/commits\//);
    return { sha: 'd'.repeat(40) };
  };
  assert.throws(() => generateRemote(args, mock), /Push the committed revision/);
  absent(output);
});

test('rejects uncommitted inputs before dispatch', t => {
  const { args, cwd, events, mock, output } = fixture(t);
  writeFileSync(join(cwd, 'pipeline.yaml'), 'version: 3\nchanged: yes\n');
  assert.throws(() => generateRemote(args, mock), /Commit changes/);
  assert.deepEqual(events, []);
  absent(output);
});

test('rejects symlinks and traversal for committed inputs', t => {
  const { args, events, mock, output } = fixture(t, { symlinkInput: true });
  assert.throws(() => generateRemote(args, mock), /Expected a committed regular file/);
  assert.throws(() => generateRemote([...args, 'safe/../escape'], mock), /repository-relative project directory/);
  assert.deepEqual(events, []);
  absent(output);
});

test('rejects a different run identity, workflow, commit, or compiler workflow pin before download', async t => {
  for (const mismatch of [
    { id: Number(runId) + 1 }, { path: '.github/workflows/other.yml' }, { head_sha: 'f'.repeat(40) },
    { head_repository: { full_name: 'other/repo' } }, { run_attempt: 2 }, { referenced_workflows: [] },
  ]) {
    await t.test(JSON.stringify(mismatch), t => {
      const { args, mock, output, run } = fixture(t);
      Object.assign(run, mismatch);
      mock.download = () => assert.fail('A mismatched run must not download anything.');
      assert.throws(() => generateRemote(args, mock), /different workflow|was retried|compiler workflow revision/);
      absent(output);
    });
  }
});

test('rejects a stale or corrupt artifact before creating output', async t => {
  for (const [name, tamper, error] of [
    ['source input hash', ({ receipt }) => { receipt.source.inputs[0].sha256 = '1'.repeat(64); }, /committed inputs/],
    ['compiler pin', ({ receipt }) => { receipt.compiler.sha = 'f'.repeat(40); }, /compiler revision or profile/],
    ['wrong attempt', ({ receipt }) => { receipt.run.attempt = '2'; }, /compiler revision or profile/],
    ['wrong run', ({ receipt }) => { receipt.run.id = '456'; }, /compiler revision or profile/],
    ['wrong Contracts release', ({ receipt }) => { receipt.compiler.contractsVersion = '26.1.1'; }, /dependency identity/],
    ['mutable dependency', ({ receipt }) => { receipt.compiler.dependencies[0].artifact = 'x-SNAPSHOT.jar'; }, /dependency identity/],
    ['wrong file hash', ({ files }) => { files['models.ts'] = 'tampered\n'; }, /digest mismatch for models.ts/],
    ...[
      ['projection source hash', { provenance: {} }],
      ['projection schema', { schemaVersion: 2 }],
      ['projection profile', { profile: 'another-profile/1' }],
      ['research projection', { kind: 'projection-probe' }],
    ].map(([name, mismatch]) => [name, ({ files, receipt }) => {
      files['projection.json'] = JSON.stringify({ ...JSON.parse(files['projection.json']), ...mismatch });
      receipt.files['projection.json'] = sha256(files['projection.json']);
    }, /projection does not match/]),
    ['extra file', ({ files }) => { files['untrusted.sh'] = 'untrusted\n'; }, /unexpected set of files/],
    ['missing file', ({ files }) => { delete files['application.ts']; }, /unexpected set of files/],
  ]) {
    await t.test(name, t => {
      const data = fixture(t);
      tamper(data);
      assert.throws(() => generateRemote(data.args, data.mock), error);
      absent(data.output);
    });
  }
});

test('rejects an artifact entry symlink even when its target has the expected contents', t => {
  const { args, cwd, files, mock, output } = fixture(t);
  const download = mock.download;
  mock.download = (repo, id, destination) => {
    download(repo, id, destination);
    writeFileSync(join(cwd, 'target.ts'), files['models.ts']);
    rmSync(join(destination, 'models.ts'));
    symlinkSync(join(cwd, 'target.ts'), join(destination, 'models.ts'));
  };
  assert.throws(() => generateRemote(args, mock), /Expected a regular file/);
  absent(output);
});

test('does not install output when the workflow failed and returns the verified diagnostic', t => {
  const { args, files, mock, output, receipt, run } = fixture(t);
  run.conclusion = 'failure';
  receipt.status = 'failed';
  for (const name of generatedFiles) delete files[name];
  files[diagnosticsFile] = 'pipeline.yaml:8 unsupported transport\n';
  receipt.files = { [diagnosticsFile]: sha256(files[diagnosticsFile]) };
  assert.throws(() => generateRemote(args, mock), /Generation failed[\s\S]*pipeline.yaml:8 unsupported transport/);
  absent(output);
});

test('a successful receipt cannot override a failed run', t => {
  const { args, mock, output, run } = fixture(t);
  run.conclusion = 'failure';
  assert.throws(() => generateRemote(args, mock), /conclusion disagree/);
  absent(output);
});

test('rechecks run and local source after download', async t => {
  for (const [name, change, error] of [
    ['workflow rerun', ({ run }) => { run.run_attempt = 2; }, /was retried/],
    ['local edit', ({ cwd }) => writeFileSync(join(cwd, 'pipeline.yaml'), 'changed: true\n'), /Commit changes/],
  ]) {
    await t.test(name, t => {
      const data = fixture(t);
      const download = data.mock.download;
      data.mock.download = (...args) => {
        download(...args);
        change(data);
      };
      assert.throws(() => generateRemote(data.args, data.mock), error);
      absent(data.output);
    });
  }
});


test('stages committed Git blobs at authored paths, including nested projects', async t => {
  for (const project of ['.', 'apps/invoice', 'generated']) {
    await t.test(project, t => {
      const { cwd } = fixture(t);
      const git = (...args) => checked('git', args, { cwd }).toString().trim();
      const directory = join(cwd, project);
      mkdirSync(directory, { recursive: true });
      writeFileSync(join(directory, 'pipeline.yaml'), 'version: 3\n');
      writeFileSync(join(directory, 'native-bindings.json'), '{}\n');
      git('add', '--', '.');
      git('-c', 'user.name=Generation test', '-c', 'user.email=test@example.invalid',
        '-c', 'commit.gpgsign=false', 'commit', '--allow-empty', '-qm', 'Project inputs');
      const revision = git('rev-parse', 'HEAD');
      // Working-tree edits must not enter the staged compiler snapshot.
      writeFileSync(join(directory, 'pipeline.yaml'), 'uncommitted: true\n');
      const inputs = gitInputs(cwd, revision, project);
      const stagingRoot = join(cwd, 'host', 'source');
      const paths = stageGitInputs(stagingRoot, inputs);
      const prefix = project === '.' ? '' : `${project}/`;
      assert.deepEqual(paths, [`${prefix}pipeline.yaml`, `${prefix}native-bindings.json`]);
      assert.equal(readFileSync(join(stagingRoot, paths[0]), 'utf8'), 'version: 3\n');
      assert.equal(readFileSync(join(stagingRoot, paths[1]), 'utf8'), '{}\n');
      assert.deepEqual(readdirSync(join(cwd, 'host')), ['source']);
    });
  }
});

test('waits for the exact dispatched run to become visible before watching it', () => {
  const calls = [];
  const delays = [];
  watchRun(repository, runId, {
    execute(command, args) {
      assert.equal(command, 'gh');
      calls.push(args);
      if (calls.length === 1) return { status: 1, stdout: Buffer.from('HTTP/2.0 404 Not Found\n') };
      return { status: 0 };
    },
    pause: delay => delays.push(delay),
  });
  const read = ['api', '--hostname', 'github.com', '--include', '--silent',
    `repos/${repository}/actions/runs/${runId}?exclude_pull_requests=true`];
  assert.deepEqual(calls, [read, read,
    ['run', 'watch', '--repo', `github.com/${repository}`, runId, '--interval', '10']]);
  assert.deepEqual(delays, [1000]);
});

test('bounds retries when a dispatched run remains invisible', () => {
  const delays = [];
  let reads = 0;
  assert.throws(() => watchRun(repository, runId, {
    execute(command, args) {
      assert.equal(command, 'gh');
      assert.equal(args[0], 'api');
      reads++;
      return { status: 1, stdout: Buffer.from('HTTP/2.0 404 Not Found\n') };
    },
    pause: delay => delays.push(delay),
  }), /not visible yet.*before dispatching again/);
  assert.equal(reads, 5);
  assert.deepEqual(delays, [1000, 2000, 4000, 8000]);
});

test('surfaces non-404 read errors without retrying or watching', () => {
  let reads = 0;
  assert.throws(() => watchRun(repository, runId, {
    execute(command, args) {
      assert.equal(command, 'gh');
      assert.equal(args[0], 'api');
      reads++;
      return { status: 1, stdout: Buffer.from('HTTP/2.0 403 Forbidden\n'),
        stderr: Buffer.from('gh: Forbidden (HTTP 403)\n') };
    },
    pause: () => assert.fail('Forbidden responses must not be retried.'),
  }), /Could not read generation run 123456.*Forbidden \(HTTP 403\)/);
  assert.equal(reads, 1);
});
