import { existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { basename, delimiter, dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  checked, compilerRepository, contractsVersion, diagnosticsFile, generatedFiles, gitInputs, inputNames, profile,
  readRegularFile, receiptFile, requireCondition, run, sha256, sourceIdentity, validRepository, validSha,
} from './node-generation-common.mjs';

const compilerRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const mavenDependencyGoal = 'org.apache.maven.plugins:maven-dependency-plugin:3.9.0:build-classpath';

function runWithDiagnostics(command, args, cwd, log) {
  const result = run(command, args, { cwd });
  for (const stream of [result.stdout, result.stderr]) {
    if (stream?.length) log.push(stream);
  }
  requireCondition(result.status === 0, `${command} failed (${result.error?.message ?? result.signal ?? result.status}).`);
}

function generate(output, temporary, compilerSha, inputs, log) {
  const localRepo = join(compilerRoot, '.m2', 'repository');
  const classpath = join(temporary, 'classpath.txt');
  const timestamp = checked('git', ['show', '-s', '--format=%ct', compilerSha], { cwd: compilerRoot }).toString().trim();
  runWithDiagnostics('./mvnw', ['-B', '-ntp', `-Dmaven.repo.local=${localRepo}`, `-Dtpf.version=${contractsVersion}`,
    '-Dmaven.test.skip=true', `-Dproject.build.outputTimestamp=${timestamp}`,
    'clean', 'package', mavenDependencyGoal, '-DincludeScope=runtime', `-Dmdep.outputFile=${classpath}`],
  compilerRoot, log);

  const target = join(compilerRoot, 'target');
  const jars = readdirSync(target).filter(name => /^pipelineframework-compiler-[0-9.]+(?:-SNAPSHOT)?\.jar$/.test(name));
  requireCondition(jars.length === 1, 'Expected one packaged compiler JAR.');
  const jar = join(target, jars[0]);
  const classpathEntries = readFileSync(classpath, 'utf8').trim().split(delimiter);
  const dependencies = classpathEntries.map(file => {
    const artifact = relative(localRepo, file);
    requireCondition(!artifact.startsWith('..') && artifact.endsWith('.jar') && !artifact.includes('SNAPSHOT'),
      'Generation requires immutable Maven dependency versions.');
    return { artifact, sha256: sha256(readFileSync(file)) };
  });
  const compiler = {
    repository: compilerRepository, sha: compilerSha, artifact: basename(jar), sha256: sha256(readFileSync(jar)),
    contractsVersion, dependencies,
  };

  const stagedInputs = inputs.map(({ bytes }, i) => {
    const path = join(temporary, inputNames[i]);
    writeFileSync(path, bytes, { flag: 'wx' });
    return path;
  });
  const generated = join(temporary, 'generated');
  const java = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin', 'java') : 'java';
  // Keep Maven progress in the log only when the compiler build itself fails.
  log.length = 0;
  runWithDiagnostics(java, ['--enable-preview', '-cp', [jar, ...classpathEntries].join(delimiter),
    'org.pipelineframework.compiler.node.NodeLocalCompiler', ...stagedInputs, generated], temporary, log);
  requireCondition(readdirSync(generated).sort().join(' ') === generatedFiles.join(' '),
    'Compiler emitted an unexpected set of files.');
  const files = Object.fromEntries(generatedFiles.map(name => [name, readRegularFile(join(generated, name))]));
  for (const [name, bytes] of Object.entries(files)) writeFileSync(join(output, name), bytes, { flag: 'wx' });
  return { compiler, files };
}

function main() {
  const [appRoot, outputArg] = process.argv.slice(2);
  requireCondition(appRoot && outputArg, 'Provide the application checkout and a new artifact directory.');
  const output = resolve(outputArg);
  const { GITHUB_REPOSITORY: repository, GITHUB_SHA: sha, GITHUB_RUN_ID: id, GITHUB_RUN_ATTEMPT: attempt,
    TPF_SOURCE_SHA: requestedSha, TPF_PROJECT: project, TPF_COMPILER_SHA: compilerSha } = process.env;
  requireCondition(validRepository(repository) && validSha(sha) && sha === requestedSha && validSha(compilerSha),
    'The dispatch must match the requested application and compiler commits.');
  requireCondition(/^\d+$/.test(id) && /^\d+$/.test(attempt), 'Expected GitHub Actions run identity.');
  requireCondition(!existsSync(output), 'The artifact directory must be new.');
  mkdirSync(output);
  const temporary = mkdtempSync(join(dirname(output), 'tpf-generation-'));
  const log = [];
  let source = { repository: repository.toLowerCase(), sha, project };
  let compiler = { repository: compilerRepository, sha: compilerSha };
  let files = {};
  let status = 'failed';
  try {
    requireCondition(checked('git', ['rev-parse', 'HEAD'], { cwd: appRoot }).toString().trim() === sha,
      'Application checkout differs from the dispatched commit.');
    requireCondition(checked('git', ['rev-parse', 'HEAD'], { cwd: compilerRoot }).toString().trim() === compilerSha,
      'Compiler checkout differs from the pinned commit.');
    requireCondition(!checked('git', ['status', '--porcelain=v1', '--untracked-files=all'], { cwd: compilerRoot }).length,
      'Use a clean checkout of the pinned compiler revision.');
    const inputs = gitInputs(appRoot, sha, project);
    source = sourceIdentity(repository, sha, project, inputs);
    ({ compiler, files } = generate(output, temporary, compilerSha, inputs, log));
    status = 'success';
  } catch (error) {
    log.push(Buffer.from(`${error.message}\n`));
    for (const name of generatedFiles) rmSync(join(output, name), { force: true });
    process.exitCode = 1;
  } finally {
    rmSync(temporary, { force: true, recursive: true });
  }
  const diagnostics = Buffer.concat(log).subarray(-1024 * 1024);
  writeFileSync(join(output, diagnosticsFile), diagnostics, { flag: 'wx' });
  const digests = Object.fromEntries(Object.entries({ ...files, [diagnosticsFile]: diagnostics })
    .map(([name, bytes]) => [name, sha256(bytes)]));
  writeFileSync(join(output, receiptFile), JSON.stringify({
    schemaVersion: 1, profile, status, run: { id, attempt }, source, compiler, files: digests,
  }, null, 2) + '\n', { flag: 'wx' });
}

try {
  main();
} catch (error) {
  console.error(error.message);
  process.exitCode = 1;
}
