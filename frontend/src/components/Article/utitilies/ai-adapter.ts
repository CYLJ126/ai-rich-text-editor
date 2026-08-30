import {createDeepSeek} from '@ai-sdk/deepseek';
import {streamText} from "ai";

export async function generateAiResponse({prompt}: { prompt: string; }) {
  // const apiKey = process.env.REACT_APP_GOOGLE_GENERATIVE_AI_API_KEY;
  const apiKey = process.env.REACT_APP_DEEPSEEK_API_KEY;
  if (!apiKey) {
    throw Error("Require deepseek api key");
  }

  const deepseek = createDeepSeek({
    apiKey: apiKey,
  });

  const result = streamText({
    model: deepseek.chat('deepseek-v4-flash'),
    prompt: prompt,
  });

  return result.toTextStreamResponse();
}

export async function simpleRequest({text, action, responseFormat}: {
  text: string,
  action: string,
  responseFormat: string
}) {
  const prompt = `According to the text: [${text}], perform the action: ${action} and return the text in ${responseFormat}, don't add any other characters into the response.`;
  const response = await generateAiResponse({prompt});
  if (!response.ok) {
    console.log('response not ok: ', response);
    throw Error(await response.text());
  }
  const reader = response.body?.getReader();
  if (!reader) {
    throw Error("No content");
  }
  let result = "";

  while (true) {
    const {done, value} = await reader.read();
    if (done) {
      break;
    }
    const chunk = new TextDecoder().decode(value);
    const size = 5;
    for (let i = 0; i < chunk.length; i += size) {
      const w = chunk.slice(i, i + size);
      result += w;
      await new Promise((resolve) => setTimeout(resolve, 25));
    }
  }
  return result;
}
