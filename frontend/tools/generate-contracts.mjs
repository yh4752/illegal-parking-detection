import fs from 'node:fs/promises';
import openapiTS, {astToString} from 'openapi-typescript';
import Ajv from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';
import standalone from 'ajv/dist/standalone/index.js';
const contract = new URL('../../contracts/effective/', import.meta.url);
const output = new URL('../src/api/generated/', import.meta.url);
const names = ['ZoneView','EventListResponse','EventDetail','AuthUser','CsrfResponse','RestError'];
await fs.mkdir(output,{recursive:true});
let types=astToString(await openapiTS(new URL('openapi.json',contract)));
// TypeScript rejects recursion through an indexed interface property (TS2502).
// Extract exactly the generated union; retain its wire semantics in a legal recursive alias.
const recursive=types.match(/        JsonValue: ([\s\S]*?);\n        Geometry:/);
if(!recursive) throw new Error('Expected JsonValue union changed; review generator.');
const ref='components["schemas"]["JsonValue"]';
const alias=recursive[1].split(ref).join('JsonValue');
types=types.replace(recursive[0],'        JsonValue: JsonValue;\n        Geometry:').split(ref).join('JsonValue');
await fs.writeFile(new URL('schema.ts',output),`export type JsonValue = ${alias};\n`+types);
const schema = JSON.parse(await fs.readFile(new URL('parking.schema.json',contract),'utf8'));
const ajv = new Ajv({strict:false,code:{source:true,esm:true},allErrors:false});
addFormats(ajv);
for (const name of names) ajv.addSchema({$schema:schema.$schema,$defs:schema.$defs,$ref:`#/$defs/${name}`},name);
let code = standalone(ajv,Object.fromEntries(names.map(n=>[`validate${n}`,n])));
// AJV emits CommonJS helper references even in ESM output. Use static ESM imports for Vite/CSP.
const imports = new Map();
code=code.replace(/require\("([^"]+)"\)/g,(_,name)=>{
 if(!imports.has(name)) imports.set(name,`runtime${imports.size}`);
 return imports.get(name);
});
for(const id of imports.values()) code=code.replaceAll(`${id}.default`, `(${id}.default ?? ${id})`);
code=[...imports].map(([name,id])=>`import ${id} from ${JSON.stringify(name+'.js')};`).join('\n')+'\n'+code;
await fs.writeFile(new URL('validators.js',output),'// Generated from contracts/effective; do not edit.\n'+code+'\n');
await fs.writeFile(new URL('validators.d.ts',output),`import type {components} from './schema';\n`+names.map(n=>`export declare function validate${n}(data: unknown): data is components['schemas']['${n}'];`).join('\n')+'\n');
console.log('Generated OpenAPI types and standalone JSON Schema validators.');
