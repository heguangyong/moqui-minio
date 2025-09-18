/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 *
 * To the extent possible under law, the author(s) have dedicated all
 * copyright and related and neighboring rights to this software to the
 * public domain worldwide. This software is distributed without any
 * warranty.
 *
 * You should have received a copy of the CC0 Public Domain Dedication
 * along with this software (see the LICENSE.md file). If not, see
 * <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package minio

import groovy.transform.CompileStatic
import org.moqui.context.ExecutionContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory

@CompileStatic
final class minio {
    protected final static Logger logger = LoggerFactory.getLogger(minio.class)

    /**
     * Execution context used to access facades.
     */
    private ExecutionContext ec

    /**
     * Initializes a new {@code minio}.
     */
    minio(ExecutionContext ec) {
        this.ec = ec

        ec.logger.info("Starting minio component");
    }
}

