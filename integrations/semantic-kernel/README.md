# prudence-semantic-kernel — Prudence governance for Semantic Kernel

A **function invocation filter** that asks Prudence before every kernel function call,
including the ones the model makes through automatic function calling. Only `ALLOW` runs the
function; anything else skips it and gives the model the refusal as the function's result.
Allowed calls are reported to Prudence.

```python
from semantic_kernel import Kernel
from semantic_kernel.filters import FilterTypes
from prudence_semantic_kernel import PrudenceFilter

kernel = Kernel()
kernel.add_plugin(ClaimsPlugin(), plugin_name="Claims")
kernel.add_filter(FilterTypes.FUNCTION_INVOCATION,
                  PrudenceFilter(ai_system_id="68bf42c1-…",
                                 pipelines={"Claims-send_customer_letter": "claims_letter"}))
```

- **Pipeline per function:** `pipelines["Plugin-function"]` or `pipelines["function"]`, or the
  function's name. A function nobody granted is denied.
- **AI system:** one id for the agent, or a mapping per function name (qualified or bare).
- **`ungoverned`:** function names that need no decision (read-only helpers).
- **Order:** the kernel runs the filter added last first. Add this one before any filter that
  changes arguments, so it decides on the arguments the function will get.
- **Agents:** a `ChatCompletionAgent` calls its plugins through its kernel, so put the filter on
  that kernel.
- **With Ernest in the suite:** pass `evidence=ErnestEvidence("https://ernest…")` so each allowed
  execution is also sealed there. Prudence compares the two sources; a run reported to Prudence
  with no evidence in Ernest shows up in Posture as `REPORTED_NOT_EVIDENCED`.
- **Credentials:** `PRUDENCE_URL`, `PRUDENCE_PDP_TOKEN_URL`, `PRUDENCE_PDP_CLIENT_ID`,
  `PRUDENCE_PDP_CLIENT_SECRET` (from `scripts/provision-runtime.py`), or pass a `PrudenceGate`.

```bash
pip install -e ../prudence-core -e .
```

Tested with semantic-kernel 1.46. How the whole integration works:
[docs/integration/README.md](../../docs/integration/README.md).
