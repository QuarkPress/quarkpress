<?php
/*
 * Copyright 2026 The QuarkWord Authors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Oracle driver for lib-wp-kses. Each case is {"fn": ..., arguments...} (see qw_kses_case in
 * kses-env.php); the result is {"out": <value>} or, when WordPress itself fails (a PHP TypeError,
 * for example), {"fatal": <exception class>}.
 */

declare(strict_types=1);

require getenv('QW_ORACLE_HOME') . '/bootstrap.php';
require __DIR__ . '/kses-env.php';

/** Thrown by the alarm: some inputs make WordPress's comment recursion loop forever (README, #9). */
final class QwTimeout extends RuntimeException
{
}

const QW_CASE_SECONDS = 2;
$qw_alarm = function_exists('pcntl_alarm');
if ($qw_alarm) {
    pcntl_async_signals(true);
    pcntl_signal(SIGALRM, function (): void {
        throw new QwTimeout();
    });
}

qw_run(function (array $in) use ($qw_alarm): array {
    try {
        if ($qw_alarm) {
            pcntl_alarm(QW_CASE_SECONDS);
        }
        return ['out' => qw_kses_case($in)];
    } catch (QwTimeout) {
        return ['fatal' => 'timeout'];
    } catch (Throwable $e) {
        return ['fatal' => get_class($e)];
    } finally {
        if ($qw_alarm) {
            pcntl_alarm(0);
        }
    }
});
