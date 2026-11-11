import type { HandlerRegistry } from '../generated/project/application.ts';

export const normalize: HandlerRegistry['normalize'] = ({value}) => ({
  kind: 'VALUE',
  value: {value: value + 250},
});

export const apply: HandlerRegistry['apply'] = ({value}) => ({
  kind: 'VALUE',
  value: {value: value * 12},
});

export const finish: HandlerRegistry['finish'] = ({value}) => ({
  kind: 'VALUE',
  value: {value: value - 100},
});

export const handlers = {normalize, apply, finish} satisfies HandlerRegistry;
