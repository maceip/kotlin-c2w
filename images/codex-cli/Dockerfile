FROM --platform=linux/riscv64 alpine:3.20

RUN apk add --no-cache \
    bash \
    curl \
    ca-certificates \
    nodejs \
    npm \
    git \
    && rm -rf /var/cache/apk/*

RUN npm install -g @openai/codex && npm cache clean --force

# Wrapper script
RUN printf '#!/bin/bash\nexec codex "$@"\n' > /usr/local/bin/start-codex \
    && chmod +x /usr/local/bin/start-codex

ENV SHELL=/bin/bash
CMD ["/usr/local/bin/start-codex"]
