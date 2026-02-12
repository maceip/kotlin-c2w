FROM --platform=linux/riscv64 alpine:3.20

RUN apk add --no-cache \
    bash \
    curl \
    ca-certificates \
    coreutils \
    grep \
    sed \
    gawk \
    less \
    vim \
    && rm -rf /var/cache/apk/*

# Default shell
ENV SHELL=/bin/bash
CMD ["/bin/bash"]
