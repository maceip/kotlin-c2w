FROM --platform=linux/riscv64 alpine:3.20

RUN apk add --no-cache \
    bash \
    curl \
    ca-certificates \
    nodejs \
    npm \
    && rm -rf /var/cache/apk/*

RUN npm install -g @google/gemini-cli && npm cache clean --force

# Wrapper script
RUN printf '#!/bin/bash\nexec gemini "$@"\n' > /usr/local/bin/start-gemini \
    && chmod +x /usr/local/bin/start-gemini

ENV SHELL=/bin/bash
CMD ["/usr/local/bin/start-gemini"]
