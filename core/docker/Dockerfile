#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
FROM eclipse-temurin:17-jdk AS builder

COPY default/apt/sources.list.d /etc/apt/sources.list.d

RUN \
    set -xeu && \
    . /etc/os-release && \
    sed -i "s/\${UBUNTU_CODENAME}/${UBUNTU_CODENAME}/g" /etc/apt/sources.list.d/* && \
    echo 'Acquire::Retries "3";' > /etc/apt/apt.conf.d/80-retries && \
    echo 'Acquire::http::Timeout "15";' > /etc/apt/apt.conf.d/80-timeouts && \
    apt-get update -q && \
    apt-get install -y -q git gcc make && \
    git clone https://github.com/airlift/jvmkill /tmp/jvmkill && \
    make -C /tmp/jvmkill

FROM eclipse-temurin:17-jdk

COPY default/apt/sources.list.d /etc/apt/sources.list.d

RUN \
    set -xeu && \
    . /etc/os-release && \
    sed -i "s/\${UBUNTU_CODENAME}/${UBUNTU_CODENAME}/g" /etc/apt/sources.list.d/* && \
    echo 'Acquire::Retries "3";' > /etc/apt/apt.conf.d/80-retries && \
    echo 'Acquire::http::Timeout "15";' > /etc/apt/apt.conf.d/80-timeouts && \
    apt-get update -q && \
    apt-get install -y -q less python3 curl && \
    rm -rf /var/lib/apt/lists/* && \
    update-alternatives --install /usr/bin/python python /usr/bin/python3 1 && \
    groupadd trino --gid 1000 && \
    useradd trino --uid 1000 --gid 1000 --create-home && \
    mkdir -p /usr/lib/trino /data/trino && \
    chown -R "trino:trino" /usr/lib/trino /data/trino

ARG TRINO_VERSION
COPY trino-cli-${TRINO_VERSION}-executable.jar /usr/bin/trino
COPY --chown=trino:trino trino-server-${TRINO_VERSION} /usr/lib/trino
COPY --chown=trino:trino default/etc /etc/trino
COPY --chown=trino:trino --from=builder /tmp/jvmkill/libjvmkill.so /usr/lib/trino/bin

EXPOSE 8080
USER trino:trino
CMD ["/usr/lib/trino/bin/run-trino"]
HEALTHCHECK --interval=10s --timeout=5s --start-period=10s \
  CMD /usr/lib/trino/bin/health-check
