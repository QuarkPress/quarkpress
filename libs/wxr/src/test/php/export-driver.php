<?php
/*
 * Copyright 2026 The QuarkPress Authors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Oracle driver for lib-wxr: {"dataset":{…}} -> {"b64": base64 of what WordPress 7.1.3's export_wp() writes
 * for a full export of the dataset}. Each case runs in its own child process (export-child.php), because
 * export_wp() can run only once per process.
 */

declare(strict_types=1);

require getenv('QP_ORACLE_HOME') . '/bootstrap.php';

qp_run(function (array $in): array {
    global $argv;
    $work = dirname($argv[3]) . '/wxr-export';
    $abspath = $work . '/abspath/';
    if (!is_dir($abspath . 'wp-includes') && !mkdir($abspath . 'wp-includes', 0777, true)) {
        throw new RuntimeException('cannot create ' . $abspath);
    }
    file_put_contents($abspath . 'wp-includes/option.php', "<?php\n");
    $dataset = $work . '/dataset.json';
    file_put_contents(
        $dataset,
        json_encode($in['dataset'], JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE | JSON_THROW_ON_ERROR)
    );
    $command = [PHP_BINARY, '-d', 'memory_limit=1G', __DIR__ . '/export-child.php', ABSPATH, $abspath, $dataset];
    $process = proc_open($command, [1 => ['pipe', 'w'], 2 => ['pipe', 'w']], $pipes);
    if (!is_resource($process)) {
        throw new RuntimeException('cannot start export-child.php');
    }
    $xml = stream_get_contents($pipes[1]);
    $errors = stream_get_contents($pipes[2]);
    fclose($pipes[1]);
    fclose($pipes[2]);
    $status = proc_close($process);
    if (0 !== $status || '' !== trim($errors)) {
        throw new RuntimeException('export-child.php exited with ' . $status . ': ' . $errors);
    }
    return ['b64' => base64_encode($xml)];
});
