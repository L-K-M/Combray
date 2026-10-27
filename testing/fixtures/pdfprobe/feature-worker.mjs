// Answers whether module workers run at all, and what they offer.
import { detect } from "./features.mjs";

detect().then(features => self.postMessage({ features }), error => self.postMessage({ error: String(error) }));
