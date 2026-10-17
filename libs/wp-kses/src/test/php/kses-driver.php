<?php
/*
 * Copyright 2026 The QuarkPress Authors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Oracle driver for lib-wp-kses. Each case is {"fn": ..., arguments...} (see qp_kses_case in
 * kses-env.php); the result is {"out": <value>} or, when WordPress itself fails (a PHP TypeError,
 * for example), {"fatal": <exception class>}.
 */

declare(strict_types=1);

require getenv('QP_ORACLE_HOME') . '/bootstrap.php';
require __DIR__ . '/kses-env.php';

/** Thrown by the alarm: some inputs make WordPress's comment recursion loop forever (README, #9). */
final class QpTimeout extends RuntimeException
{
}

const QP_CASE_SECONDS = 2;
$qp_alarm = function_exists('pcntl_alarm');
if ($qp_alarm) {
    pcntl_async_signals(true);
    pcntl_signal(SIGALRM, function (): void {
        throw new QpTimeout();
    });
}

qp_run(function (array $in) use ($qp_alarm): array {
    try {
        if ($qp_alarm) {
            pcntl_alarm(QP_CASE_SECONDS);
        }
        return ['out' => qp_kses_case($in)];
    } catch (QpTimeout) {
        return ['fatal' => 'timeout'];
    } catch (Throwable $e) {
        return ['fatal' => get_class($e)];
    } finally {
        if ($qp_alarm) {
            pcntl_alarm(0);
        }
    }
});
